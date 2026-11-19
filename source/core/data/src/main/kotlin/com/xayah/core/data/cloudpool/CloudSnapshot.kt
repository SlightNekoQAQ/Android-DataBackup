package com.xayah.core.data.cloudpool

import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
        onProgress: (Int, Int) -> Unit = { _, _ -> },
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
            onProgress(0, sources.size)
            sources.forEachIndexed { index, source ->
                // The user-visible archive path lives only in the encrypted manifest.
                val encrypted = File.createTempFile("payload-", ".partial", privateStaging)
                try {
                    CloudPayload.encrypt(source.file, encrypted, passphrase, source.relativePath)
                    entries += transfer.store(encrypted, source.relativePath, "db-$id-$index.bin", targets, useManualOrder)
                } finally {
                    encrypted.delete()
                }
                onProgress(index + 1, sources.size)
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
    suspend fun retrieve(manifest: ByteArray, passphrase: CharArray, onProgress: (Int, Int) -> Unit = { _, _ -> }): File = withContext(Dispatchers.IO) {
        val archives = CloudManifest.decode(manifest, passphrase)
        val missing = archives.map(CloudArchive::accountId).toSet() - drives.map(CloudDrive::accountId).toSet()
        if (missing.isNotEmpty()) throw IOException("One or more required cloud accounts are unavailable")
        val staging = File(privateStaging, "snapshot-${UUID.randomUUID()}")
        check(staging.mkdirs())
        val transfer = CloudTransfer(drives)
        try {
            val incoming = File(privateStaging, "incoming-${UUID.randomUUID()}")
            check(incoming.mkdirs())
            try {
                onProgress(0, archives.size)
                val slots = Semaphore(3)
                val progressLock = Any()
                var completed = 0
                coroutineScope {
                    archives.groupBy(CloudArchive::accountId).values.map { accountArchives ->
                        async {
                            for (archive in accountArchives) slots.withPermit {
                                val encrypted = transfer.retrieve(archive, incoming)
                                val plain = File(staging, archive.relativePath)
                                val parent = checkNotNull(plain.parentFile)
                                check(parent.isDirectory || parent.mkdirs() || parent.isDirectory)
                                CloudPayload.decrypt(encrypted, plain, passphrase, archive.relativePath)
                                encrypted.delete()
                                synchronized(progressLock) { onProgress(++completed, archives.size) }
                            }
                        }
                    }.awaitAll()
                }
            } finally {
                incoming.deleteRecursively()
            }
            staging
        } catch (error: Exception) {
            staging.deleteRecursively()
            throw error
        }
    }
}
