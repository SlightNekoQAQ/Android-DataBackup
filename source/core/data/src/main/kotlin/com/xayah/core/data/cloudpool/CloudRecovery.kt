package com.xayah.core.data.cloudpool

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Shared by the restore screen and protocol-level simulations. Only drive I/O is injected. */
class CloudRecovery(private val drives: List<CloudDrive>, private val staging: File) {
    data class RemoteObject(val id: String, val name: String, val bytes: Long)
    data class Reference(val accountId: String, val remote: RemoteObject)

    suspend fun discover(): List<Reference> = withContext(Dispatchers.IO) {
        val manifests = mutableListOf<Reference>()
        var listed = 0
        drives.forEach { drive ->
            try {
                manifests += drive.listObjects().filter(::isManifest).map { Reference(drive.accountId, it) }
                listed++
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // A missing drive must not hide otherwise reachable index copies.
            }
        }
        if (drives.isNotEmpty() && listed == 0) throw IOException("Cannot list any configured backup folder")
        manifests
    }

    suspend fun readManifest(accountId: String, objectId: String): ByteArray = withContext(Dispatchers.IO) {
        val drive = drives.singleOrNull { it.accountId == accountId } ?: throw IOException("Backup account is not configured")
        val remote = drive.listObjects().singleOrNull { it.id == objectId && isManifest(it) }
            ?: throw IOException("Backup manifest is missing")
        check(staging.isDirectory || staging.mkdirs() || staging.isDirectory)
        val file = File.createTempFile("index-", ".partial", staging)
        try {
            drive.downloadManifest(remote.id, file)
            if (file.length() != remote.bytes) throw IOException("Incomplete backup manifest")
            file.readBytes()
        } finally {
            file.delete()
        }
    }

    suspend fun restore(accountId: String, objectId: String, onProgress: (Int, Int) -> Unit = { _, _ -> }): File =
        CloudSnapshot(drives, staging).retrieve(readManifest(accountId, objectId), onProgress)

    private fun isManifest(remote: RemoteObject) = remote.bytes in 1..MAX_MANIFEST &&
        remote.name.startsWith("db-") && remote.name.endsWith(CloudManifest.FILE_NAME)

    private companion object {
        const val MAX_MANIFEST = 8L * 1024 * 1024
    }
}
