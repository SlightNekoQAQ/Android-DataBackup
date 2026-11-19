package com.xayah.core.data.cloudpool

import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A collection of finalized DataBackup archives and sidecars, indexed by their relative paths. */
class CloudSnapshot(private val drives: List<CloudDrive>, private val privateStaging: File) {
    data class Source(val relativePath: String, val file: File)
    data class Published(val id: String, val manifestCopies: Map<String, String>, val missingCopies: Set<String>)

    suspend fun publish(
        sources: List<Source>,
        targets: List<CloudTarget>,
        passphrase: CharArray,
        useManualOrder: Boolean = false,
    ): Published = withContext(Dispatchers.IO) {
        require(passphrase.size >= 8)
        require(sources.isNotEmpty() && sources.map(Source::relativePath).distinct().size == sources.size)
        sources.forEach { validateRelativePath(it.relativePath); require(it.file.isFile && it.file.canRead()) }
        val id = UUID.randomUUID().toString()
        val transfer = CloudTransfer(drives)
        val entries = ArrayList<CloudArchive>(sources.size)
        check(privateStaging.isDirectory || privateStaging.mkdirs())
        val temporary = File.createTempFile("manifest-", ".partial", privateStaging)
        try {
            sources.forEachIndexed { index, source ->
                // The user-visible archive path lives only in the encrypted manifest.
                entries += transfer.store(source.file, source.relativePath, "db-$id-$index.bin", targets, useManualOrder)
            }
            temporary.writeBytes(CloudManifest.encode(entries, passphrase))
            val copies = linkedMapOf<String, String>()
            val missing = linkedSetOf<String>()
            val used = entries.map(CloudArchive::accountId).toSet()
            for (drive in drives.filter { it.accountId in used }) {
                try {
                    val reference = drive.upload(temporary, "db-$id-${CloudManifest.FILE_NAME}")
                    if (reference.isBlank()) throw IOException("Provider did not confirm manifest upload")
                    copies[drive.accountId] = reference
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    missing += drive.accountId
                }
            }
            if (copies.isEmpty()) throw IOException("Cloud backup has no recoverable manifest")
            Published(id, copies, missing)
        } finally {
            temporary.delete()
        }
    }

    /** Downloads verified files to a new tree. An incomplete tree never reaches the restore caller. */
    suspend fun retrieve(manifest: ByteArray, passphrase: CharArray): File = withContext(Dispatchers.IO) {
        val archives = CloudManifest.decode(manifest, passphrase)
        val missing = archives.map(CloudArchive::accountId).toSet() - drives.map(CloudDrive::accountId).toSet()
        if (missing.isNotEmpty()) throw IOException("One or more required cloud accounts are unavailable")
        val staging = File(privateStaging, "snapshot-${UUID.randomUUID()}")
        check(staging.mkdirs())
        val transfer = CloudTransfer(drives)
        try {
            archives.forEach { transfer.retrieve(it, staging) }
            staging
        } catch (error: Exception) {
            staging.deleteRecursively()
            throw error
        }
    }
}
