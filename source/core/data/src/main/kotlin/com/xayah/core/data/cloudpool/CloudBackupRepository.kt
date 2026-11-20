package com.xayah.core.data.cloudpool

import android.content.Context
import com.yunx.app.data.network.model.ShareFile
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Keeps the cloud job separate from the existing local backup and restore database. */
class CloudBackupRepository(context: Context) {
    private val context = context.applicationContext
    private val staging = File(this.context.noBackupFilesDir, "cloud-backup")
    private val vault = CloudAccountVault(this.context)
    private val preferences = CloudDestinationPreferences(this.context)

    private inner class Configured(val slot: Int, val account: ImportedCloudAccount, val folder: String, val identity: String) {
        // Keep stable identity for manifest matching and folder ID for provider API routing.
        val key: String get() = identity
        val driveId: String get() = "${account.platform}:$folder"
        fun asDrive() = YunxCloudDrive(context, account, driveId, folder, preferences.connections(slot))
    }

    data class RemoteBackup(
        val label: String,
        val provider: String,
        val accountKey: String,
        val objectId: String,
        val backupId: String = label.removePrefix("db-").substringBefore("-") ,
        val createdAtMillis: Long = 0L,
    )

    private fun checkUnique(destinations: List<Configured>): List<Configured> = destinations.also {
        require(it.map { item -> item.driveId }.distinct().size == it.size) { "Duplicate cloud drive destinations" }
    }

    private suspend fun configured(includeLegacy: Boolean = false, verifyIdentity: Boolean = true, providers: Set<String>? = null): List<Configured> {
        val accounts = vault.load().mapIndexedNotNull { slot, account ->
            if (!YunxCredentialImport.supports(account.platform) || (providers != null && account.platform !in providers)) return@mapIndexedNotNull null
            val folder = preferences.defaultFolder(slot) ?: preferences.read(slot).takeIf { includeLegacy } ?: return@mapIndexedNotNull null
            val identity = if (!verifyIdentity) "${account.platform}:${account.identity.ifBlank { slot.toString() }}" else try {
                account.verifiedIdentity(context)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                "${account.platform}:unverified:${account.identity.ifBlank { slot.toString() }}"
            }
            Configured(slot, account, folder, identity)
        }
        return checkUnique(accounts)
    }

    data class DestinationResult(val slot: Int, val ready: Boolean)

    /** Resolve the provider's folder ID from its root listing, not by assuming IDs are paths. */
    suspend fun prepareDestinations(): List<DestinationResult> = withContext(Dispatchers.IO) {
        vault.load().mapIndexedNotNull { slot, account ->
            if (!YunxCredentialImport.supports(account.platform)) return@mapIndexedNotNull null
            val ready = try {
                val drive = account.personalDrive(context)
                suspend fun findFolder(): ShareFile? {
                    val matches = drive.list(drive.rootId).filter { it.fname == DEFAULT_FOLDER }
                    if (matches.size > 1 || matches.any { !it.isdir }) throw IOException("Conflicting /backup entry")
                    return matches.singleOrNull()
                }
                val folder = findFolder()?.let(drive::directoryId) ?: try {
                    drive.createDirectory(drive.rootId, DEFAULT_FOLDER)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    findFolder()?.let(drive::directoryId) ?: throw error
                }
                require(folder.isNotBlank())
                preferences.saveDefaultFolder(slot, folder)
                true
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                false
            }
            DestinationResult(slot, ready)
        }
    }


    private val receipts = this.context.getSharedPreferences("cloud-backup-receipts", Context.MODE_PRIVATE)

    fun lastConfirmedUpload(): String? = receipts.getString("snapshot-and-providers", null)

    fun hasDestinations(): Boolean = vault.load().any { YunxCredentialImport.supports(it.platform) }

    suspend fun publish(localRoot: File, folders: Set<String>? = null, onProgress: (Int, Int) -> Unit = { _, _ -> }): CloudSnapshot.Published = withContext(Dispatchers.IO) {
        val root = localRoot.canonicalFile
        val sources = (folders ?: setOf("apps", "files", "configs")).flatMap { category ->
            validateRelativePath(category)
            val base = File(root, category)
            if (!base.isDirectory) emptyList() else collect(root, base)
        }
        val session = begin()
        onProgress(0, sources.size)
        sources.forEachIndexed { index, source ->
            session.append(source.file, source.relativePath)
            onProgress(index + 1, sources.size)
        }
        session.finish()
    }

    class UploadSession internal constructor(private val writer: CloudSnapshot.Writer, private val confirm: (CloudSnapshot.Published) -> Unit) {
        suspend fun append(file: File, relativePath: String, afterConfirmedUpload: suspend () -> Unit = {}) =
            writer.append(CloudSnapshot.Source(relativePath, file), afterConfirmedUpload)
        suspend fun finish() = writer.finish().also(confirm)
    }

    suspend fun begin(): UploadSession = withContext(Dispatchers.IO) {
        val ready = prepareDestinations().filter { it.ready }.map { it.slot }.toSet()
        val accounts = configured().filter { it.slot in ready }
        require(accounts.isNotEmpty()) { "Could not locate or create /backup on any account" }
        require(accounts.none { ":unverified:" in it.identity }) { "Could not verify one or more cloud accounts; re-import them from YunX" }
        val drives = accounts.map { it.asDrive() }
        val manualOrder = preferences.manualOrder()
        val ordered = uploadSlotOrder(accounts.map { it.slot }, manualOrder)
        // A provider quota probe must never veto an otherwise writable account. Unknown quota is
        // "unknown", not "full"; only explicit provider rejections cause failover at upload time.
        val targets = drives.zip(accounts).map { (drive, account) ->
            CloudTarget(
                accountId = account.driveId,
                priority = ordered.indexOf(account.slot),
                downloadBytesPerSecond = preferences.speed(account.slot),
                freeBytes = runCatching { drive.quotaSnapshot() }.getOrNull(),
                maxFileBytes = drive.maxFileBytes,
            )
        }
        UploadSession(CloudSnapshot(drives, staging).begin(targets, useManualOrder = manualOrder != null)) { published ->
            val confirmedProviders = accounts.filter { it.driveId in published.manifestCopies }.map { it.account.platform }.distinct()
            receipts.edit().putString("snapshot-and-providers", "${published.id} · ${confirmedProviders.joinToString()}").apply()
        }
    }

    suspend fun measureSpeed(slot: Int): Long = withContext(Dispatchers.IO) {
        val account = configured().singleOrNull { it.slot == slot } ?: throw IOException("Backup folder is not set")
        val entry = account.account.personalDrive(context).list(account.folder)
            .filter { !it.isdir && it.fsize in (1024L * 1024)..(16L * 1024 * 1024) }
            .maxByOrNull { it.fsize } ?: throw IOException("No suitable test file in backup directory")
        check(staging.isDirectory || staging.mkdirs())
        val temp = File.createTempFile("speed-", ".partial", staging)
        try {
            val start = System.nanoTime()
            account.asDrive().download(entry.fid, temp)
            if (temp.length() != entry.fsize) throw IOException("Incomplete speed-test download")
            val speed = (entry.fsize * 1_000_000_000L / (System.nanoTime() - start).coerceAtLeast(1L)).coerceAtLeast(1L)
            preferences.recordSpeed(slot, speed)
            speed
        } finally {
            temp.delete()
        }
    }

    suspend fun available(): List<RemoteBackup> = withContext(Dispatchers.IO) {
        if (configured(verifyIdentity = false).isEmpty()) prepareDestinations()
        // Discovery only lists index copies; never perform one network identity lookup per
        // configured account while rendering the restore screen.
        val accounts = configured(includeLegacy = true, verifyIdentity = false)
        val found = mutableListOf<RemoteBackup>()
        for (account in accounts) {
            try {
                CloudRecovery(listOf(account.asDrive()), staging).discover().filter {
                    it.remote.name.endsWith(CloudManifest.FILE_NAME)
                }.forEach {
                    val backupId = it.remote.name.removePrefix("db-").substringBefore("-${CloudManifest.FILE_NAME}")
                    val timestamp = backupId.substringBefore('-').toLongOrNull() ?: 0L
                    found += RemoteBackup(it.remote.name, account.account.platform, account.driveId, it.remote.id, backupId, timestamp)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // A wrong/expired account must not hide backups found through other accounts.
            }
        }
        found.distinctBy { it.backupId }.sortedByDescending { it.createdAtMillis }
    }

    data class FileLocation(
        val original: String,
        val provider: String,
        val account: String,
        val remoteName: String?,
        val folder: String,
        val accountIdentity: String = "",
        val available: Boolean = false,
    )

    data class SnapshotContents(val entries: List<FileLocation>, val missingAccounts: Set<String>)

    /** Read the snapshot index from any logged-in account, then diagnose each required account. */
    suspend fun locations(backup: RemoteBackup): List<FileLocation> = snapshotContents(backup).entries

    suspend fun snapshotContents(backup: RemoteBackup): SnapshotContents = withContext(Dispatchers.IO) {
        val allAccounts = configured(includeLegacy = true)
        val manifestOwner = allAccounts.singleOrNull { it.driveId == backup.accountKey }
            ?: throw IOException("The account containing this backup index is not connected")
        val manifest = CloudRecovery(listOf(manifestOwner.asDrive()), staging).readManifest(manifestOwner.driveId, backup.objectId)
        val archives = CloudManifest.decode(manifest)
        val byIdentity = allAccounts.associateBy { it.identity }
        val contents = CloudManifest.decodeContents(manifest)
        val entries = contents.archives.map { archive ->
            val requiredIdentity = archive.accountIdentity
            val account = byIdentity[requiredIdentity]
            FileLocation(archive.relativePath, requiredIdentity.substringBefore(':'), account?.account?.nickname.orEmpty(),
                null, account?.folder?.let { "/backup" }.orEmpty(), requiredIdentity, account != null)
        }
        SnapshotContents(entries, contents.archives.map { it.accountIdentity }.toSet() - byIdentity.keys)
    }

    data class RestoreResult(val folder: File, val missing: List<CloudArchive>, val unavailableEntries: List<String> = emptyList(), val unverifiablePlatforms: Set<String> = emptySet())

    /** Restore only payloads whose verified account is present; report the rest to the caller. */
    suspend fun restoreAvailable(backup: RemoteBackup, onProgress: (Int, Int) -> Unit = { _, _ -> }): RestoreResult = restoreAvailable(backup, null, onProgress)

    suspend fun restoreAvailable(backup: RemoteBackup, selectedEntries: Set<String>?, onProgress: (Int, Int) -> Unit = { _, _ -> }): RestoreResult = withContext(Dispatchers.IO) {
        val allAccounts = configured(includeLegacy = true, verifyIdentity = false)
        val manifestOwner = allAccounts.singleOrNull { it.driveId == backup.accountKey }
            ?: throw IOException("当前连接中找不到这个备份的索引副本，请确认已导入保存该备份的 YunX 账号。")
        val manifest = CloudRecovery(listOf(manifestOwner.asDrive()), staging).readManifest(manifestOwner.driveId, backup.objectId)
        val decoded = CloudManifest.decodeContents(manifest)
        val selectedPaths = selectedEntries?.mapNotNull { selected -> decoded.entries.firstOrNull { it.path == selected }?.path }?.toSet()
        require(selectedEntries == null || selectedPaths == selectedEntries) { "Selected backup entry is not present in the index" }
        val selectedItems = decoded.entries.filter { selectedPaths == null || it.path in selectedPaths }
        val relevantArchives = if (selectedPaths == null) decoded.archives else decoded.archives.filter { archive ->
            selectedItems.any { it.includesArchive(archive.relativePath) }
        }
        val requiredIdentities = relevantArchives.map { it.accountIdentity }.toSet()
        val requiredProviders = requiredIdentities.map { it.substringBefore(':') }.toSet()
        val accounts = configured(includeLegacy = true, providers = requiredProviders)
        val byIdentity = accounts.associateBy { it.identity }
        val availableByIdentity = byIdentity.filterValues { !it.identity.contains(":unverified:") && it.key in requiredIdentities }
        val missing = relevantArchives.filter { it.accountIdentity !in availableByIdentity }
        val wrongAccounts = accounts.filter { it.identity.contains(":unverified:") }.map { it.account.platform }.toSet()
        val accessibleArchives = relevantArchives.filter { it.accountIdentity in availableByIdentity }
        val accessibleDrives = accessibleArchives.map { archive ->
            availableByIdentity.getValue(archive.accountIdentity).asDrive()
        }.distinctBy { it.accountId }
        // Translate stable cloud account identity to this device's provider folder routing ID.
        val routedArchives = accessibleArchives.map { archive ->
            archive.copy(accountId = availableByIdentity.getValue(archive.accountIdentity).driveId)
        }
        if (accessibleArchives.isEmpty()) {
            check(staging.isDirectory || staging.mkdirs())
            val emptyFolder = File(staging, "snapshot-empty-${java.util.UUID.randomUUID()}")
            check(emptyFolder.mkdirs())
            val unavailable = decoded.entries.filter { selectedPaths == null || it.path in selectedPaths }
                .map { it.displayName }
            return@withContext RestoreResult(emptyFolder, missing, unavailable, wrongAccounts)
        }
        // This internal index routes available archives only. Entries in the original index can
        // legitimately reference missing archives and must not invalidate the available subset.
        val retrievalIndex = CloudManifest.encode(routedArchives)
        val retrieval = CloudSnapshot(accessibleDrives, staging).retrieveAvailable(retrievalIndex, onProgress)
        val failedPaths = (missing + retrieval.unavailable).map { it.relativePath }.toSet()
        val unavailableEntries = selectedItems.filter { entry -> failedPaths.any(entry::includesArchive) }.map { it.displayName }
        RestoreResult(retrieval.folder, missing + retrieval.unavailable, unavailableEntries, wrongAccounts)
    }

    suspend fun restore(backup: RemoteBackup, onProgress: (Int, Int) -> Unit = { _, _ -> }): File =
        restoreAvailable(backup, onProgress).folder

    data class BackupContents(val archives: List<CloudArchive>, val entries: List<CloudManifest.BackupEntry>, val missingAccounts: Set<String>)

    suspend fun backupContents(backup: RemoteBackup): BackupContents = withContext(Dispatchers.IO) {
        // Reading a selected index must be cheap and must not validate every account again.
        val accounts = configured(includeLegacy = true, verifyIdentity = false)
        val owner = accounts.singleOrNull { it.driveId == backup.accountKey }
            ?: throw IOException("Backup index account is not connected")
        val contents = CloudManifest.decodeContents(CloudRecovery(listOf(owner.asDrive()), staging).readManifest(owner.driveId, backup.objectId))
        // At preview time these are required identities, not proof that a local account is
        // wrong. Actual account verification is deferred until the user starts a restore.
        val connected = accounts.map { it.key }.toSet()
        val missing = contents.archives.map { it.accountIdentity }.toSet() - connected
        BackupContents(contents.archives, contents.entries, missing)
    }

    suspend fun contents(backup: RemoteBackup): CloudManifest.Contents = backupContents(backup).let {
        CloudManifest.Contents(it.archives, it.entries)
    }

    suspend fun discoverBackups(): List<RemoteBackup> = available()

    suspend fun selectedRestoreFolder(folder: File, entries: List<CloudManifest.BackupEntry>, selected: Set<String>, missing: List<CloudArchive> = emptyList()): File = withContext(Dispatchers.IO) {
        val root = folder.canonicalFile
        val stagingRoot = staging.canonicalFile
        require(root.path.startsWith(stagingRoot.path + File.separator))
        val selectedEntries = entries.filter { it.path in selected }
        val indexFiles = selectedEntries.filter { entry -> missing.none { entry.includesArchive(it.relativePath) } }
            .map { it.archive }.toSet()
        listOf("apps" to "package_restore_config.json", "files" to "media_restore_config.json").forEach { (category, name) ->
            File(root, category).walkTopDown().filter { it.isFile && it.name == name }
                .filter { it.relativeTo(root).invariantSeparatorsPath !in indexFiles }.forEach { it.delete() }
        }
        File(root, "apps").mkdirs()
        File(root, "files").mkdirs()
        root
    }


    private fun collect(root: File, directory: File): List<CloudSnapshot.Source> {
        if (directory.absoluteFile != directory.canonicalFile) throw IOException("Backup path is not a regular directory")
        val result = mutableListOf<CloudSnapshot.Source>()
        val pending = ArrayDeque<File>().apply { add(directory) }
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            if (current.absoluteFile != current.canonicalFile) throw IOException("Symbolic paths are not valid backups")
            if (current.isDirectory) {
                val children = current.listFiles() ?: throw IOException("Cannot read local backup directory")
                pending.addAll(children)
            } else if (current.isFile) {
                val path = current.relativeTo(root).invariantSeparatorsPath
                validateRelativePath(path)
                // The original app includes protocol passwords in configurations.json.
                if (path != "configs/configurations.json") result += CloudSnapshot.Source(path, current)
            }
        }
        return result.sortedBy { it.relativePath }
    }

    private companion object {
        const val DEFAULT_FOLDER = "backup"
    }
}
