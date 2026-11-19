package com.xayah.core.data.cloudpool

import android.content.Context
import com.yunx.app.data.network.model.ShareFile
import java.io.File
import java.io.IOException
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

    private fun configured(): List<Configured> = vault.load().mapIndexedNotNull { slot, account ->
        preferences.read(slot)?.let { Configured(slot, account, it) }
    }.also { require(it.map { item -> item.key }.distinct().size == it.size) { "Duplicate backup destinations" } }

    private fun Configured.asDrive() = YunxCloudDrive(context, account, key, folder, preferences.connections(slot))

    suspend fun publish(localRoot: File, password: CharArray, onProgress: (Int, Int) -> Unit = { _, _ -> }): CloudSnapshot.Published = withContext(Dispatchers.IO) {
        val accounts = configured()
        require(accounts.isNotEmpty()) { "Select a backup folder on at least one cloud account" }
        val root = localRoot.canonicalFile
        val sources = listOf("apps", "files", "configs").flatMap { category ->
            val base = File(root, category)
            if (!base.isDirectory) emptyList() else collect(root, base)
        }
        val drives = accounts.map { it.asDrive() }
        val targets = drives.zip(accounts).map { (drive, account) -> drive.target(account.slot, preferences.speed(account.slot)) }
        CloudSnapshot(drives, staging).publish(sources, targets, password, onProgress = onProgress)
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
        configured().flatMap { account ->
            account.account.personalDrive(context).list(account.folder)
                .filter { it.isManifest() && it.fsize in 1..MAX_MANIFEST }
                .map { RemoteBackup(it.fname, account.account.platform, account.key, it.fid) }
        }
    }

    suspend fun restore(backup: RemoteBackup, password: CharArray, onProgress: (Int, Int) -> Unit = { _, _ -> }): File = withContext(Dispatchers.IO) {
        val accounts = configured()
        val source = accounts.singleOrNull { it.key == backup.accountKey } ?: throw IOException("Backup account is not configured")
        val remote = source.account.personalDrive(context).list(source.folder).singleOrNull { it.fid == backup.objectId && it.isManifest() }
            ?: throw IOException("Backup manifest is missing")
        if (remote.fsize !in 1..MAX_MANIFEST) throw IOException("Invalid backup manifest size")
        check(staging.isDirectory || staging.mkdirs())
        val temp = File.createTempFile("index-", ".partial", staging)
        try {
            source.asDrive().download(remote.fid, temp)
            if (temp.length() != remote.fsize) throw IOException("Incomplete backup manifest")
            CloudSnapshot(accounts.map { it.asDrive() }, staging).retrieve(temp.readBytes(), password, onProgress)
        } finally {
            temp.delete()
        }
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
                result += CloudSnapshot.Source(path, current)
            }
        }
        return result.sortedBy { it.relativePath }
    }

    private fun ShareFile.isManifest() = !isdir && fname.startsWith("db-") && fname.endsWith(CloudManifest.FILE_NAME)

    private companion object {
        const val MAX_MANIFEST = 8L * 1024 * 1024
    }
}
