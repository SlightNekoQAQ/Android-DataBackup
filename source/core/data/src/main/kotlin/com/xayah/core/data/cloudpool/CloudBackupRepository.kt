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

    private data class Configured(val slot: Int, val account: ImportedCloudAccount, val folder: String) {
        val key = "${account.platform}:$folder"
    }

    data class RemoteBackup(val label: String, val provider: String, val accountKey: String, val objectId: String)

    private fun checkUnique(destinations: List<Configured>): List<Configured> = destinations.also {
        require(it.map { item -> item.key }.distinct().size == it.size) { "Duplicate backup destinations" }
    }

    private fun configured(includeLegacy: Boolean = false): List<Configured> = checkUnique(vault.load().flatMapIndexed { slot, account ->
        if (!YunxCredentialImport.supports(account.platform)) return@flatMapIndexed emptyList()
        val current = preferences.defaultFolder(slot)
        listOfNotNull(current, preferences.read(slot).takeIf { includeLegacy }).distinct().map { Configured(slot, account, it) }
    })

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
                require(folder.isNotBlank() && folder != drive.rootId)
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

    private fun Configured.asDrive() = YunxCloudDrive(context, account, key, folder, preferences.connections(slot))

    private val receipts = this.context.getSharedPreferences("cloud-backup-receipts", Context.MODE_PRIVATE)

    fun lastConfirmedUpload(): String? = receipts.getString("snapshot-and-providers", null)

    fun hasDestinations(): Boolean = vault.load().any { YunxCredentialImport.supports(it.platform) }

    suspend fun publish(localRoot: File, password: CharArray, folders: Set<String>? = null, onProgress: (Int, Int) -> Unit = { _, _ -> }): CloudSnapshot.Published = withContext(Dispatchers.IO) {
        val root = localRoot.canonicalFile
        val sources = (folders ?: setOf("apps", "files", "configs")).flatMap { category ->
            validateRelativePath(category)
            val base = File(root, category)
            if (!base.isDirectory) emptyList() else collect(root, base)
        }
        val session = begin(password)
        onProgress(0, sources.size)
        sources.forEachIndexed { index, source ->
            session.append(source.file, source.relativePath, deleteAfterUpload = false)
            onProgress(index + 1, sources.size)
        }
        session.finish()
    }

    class UploadSession internal constructor(private val writer: CloudSnapshot.Writer, private val confirm: (CloudSnapshot.Published) -> Unit) {
        suspend fun append(file: File, relativePath: String, deleteAfterUpload: Boolean = true) =
            writer.append(CloudSnapshot.Source(relativePath, file), deleteAfterUpload)
        suspend fun finish() = writer.finish().also(confirm)
    }

    suspend fun begin(password: CharArray): UploadSession = withContext(Dispatchers.IO) {
        val ready = prepareDestinations().filter { it.ready }.map { it.slot }.toSet()
        val accounts = configured().filter { it.slot in ready }
        require(accounts.isNotEmpty()) { "Could not locate or create /backup on any account" }
        val drives = accounts.map { it.asDrive() }
        val manualOrder = preferences.manualOrder()
        val ordered = uploadSlotOrder(accounts.map { it.slot }, manualOrder)
        val targets = drives.zip(accounts).map { (drive, account) -> drive.target(ordered.indexOf(account.slot), preferences.speed(account.slot)) }
        UploadSession(CloudSnapshot(drives, staging).begin(targets, password, useManualOrder = manualOrder != null)) { published ->
            val confirmedProviders = accounts.filter { it.key in published.manifestCopies }.map { it.account.platform }.distinct()
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
        if (configured().isEmpty()) prepareDestinations()
        val accounts = configured(includeLegacy = true)
        val providers = accounts.associate { it.key to it.account.platform }
        CloudRecovery(accounts.map { it.asDrive() }, staging).discover().map {
            RemoteBackup(it.remote.name, providers.getValue(it.accountId), it.accountId, it.remote.id)
        }
    }

    data class FileLocation(val original: String, val provider: String, val account: String, val remoteName: String?, val folder: String)

    suspend fun locations(backup: RemoteBackup, password: CharArray): List<FileLocation> = withContext(Dispatchers.IO) {
        val accounts = configured(includeLegacy = true)
        val manifest = CloudRecovery(accounts.map { it.asDrive() }, staging).readManifest(backup.accountKey, backup.objectId)
        val archives = CloudManifest.decode(manifest, password)
        val locations = mutableMapOf<String, Map<String, String>>()
        for (account in accounts.filter { target -> archives.any { it.accountId == target.key } }) {
            try {
                locations[account.key] = account.account.personalDrive(context).list(account.folder)
                    .filterNot { it.isdir }.associate { it.fid to it.fname }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Keep showing other disks when one account cannot be listed.
            }
        }
        archives.map { archive ->
            val account = accounts.firstOrNull { it.key == archive.accountId }
            FileLocation(archive.relativePath, archive.accountId.substringBefore(':'), account?.account?.nickname.orEmpty(),
                locations[archive.accountId]?.get(archive.fileId),
                if (account != null && preferences.defaultFolder(account.slot) == account.folder) "/backup" else "")
        }
    }

    suspend fun restore(backup: RemoteBackup, password: CharArray, onProgress: (Int, Int) -> Unit = { _, _ -> }): File = withContext(Dispatchers.IO) {
        val drives = configured(includeLegacy = true).map { it.asDrive() }
        CloudRecovery(drives, staging).restore(backup.accountKey, backup.objectId, password, onProgress)
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
