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

/**
 * A collection of finalized DataBackup archives and sidecars, indexed by their relative paths.
 * Archives travel to the user's own cloud accounts unmodified; only size and SHA-256 are checked,
 * so recovery needs nothing beyond the account credentials.
 */
class CloudSnapshot(private val drives: List<CloudDrive>, private val privateStaging: File) {
    data class Source(val relativePath: String, val file: File)
    data class Published(val id: String, val manifestCopies: Map<String, String>, val missingCopies: Set<String>)

    fun begin(targets: List<CloudTarget>, useManualOrder: Boolean = false): Writer = Writer(targets, useManualOrder)

    inner class Writer internal constructor(private val targets: List<CloudTarget>, private val manual: Boolean) {
        private val id = UUID.randomUUID().toString()
        private val transfer = CloudTransfer(drives)
        private val entries = mutableListOf<CloudArchive>()
        private suspend fun identity(accountId: String): String {
            val drive = drives.single { it.accountId == accountId }
            return (drive as? YunxCloudDrive)?.verifiedAccountIdentity() ?: accountId
        }
        private var finished = false
        private var failed = false

        suspend fun append(source: Source, afterConfirmedUpload: suspend () -> Unit = {}) = withContext(Dispatchers.IO) {
            check(!finished && !failed)
            try {
                validateRelativePath(source.relativePath)
                require(source.file.isFile && source.file.canRead() && entries.none { it.relativePath == source.relativePath })
                // The user-visible archive path lives in the index; payloads stay flat and opaque.
                val uploaded = transfer.store(source.file, source.relativePath, "db-$id-${entries.size}.bin", targets, manual)
                entries += uploaded.copy(accountIdentity = identity(uploaded.accountId))
                afterConfirmedUpload()
            } catch (error: CancellationException) {
                failed = true
                throw error
            } catch (error: Exception) {
                failed = true
                throw error
            }
        }

        suspend fun finish(): Published = withContext(Dispatchers.IO) {
            check(!finished && !failed && entries.isNotEmpty())
            finished = true
            check(privateStaging.isDirectory || privateStaging.mkdirs() || privateStaging.isDirectory)
            val temporary = File.createTempFile("index-", ".partial", privateStaging)
            try {
                temporary.writeBytes(CloudManifest.encode(entries))
                val copies = linkedMapOf<String, String>()
                val missing = linkedSetOf<String>()
                // Replicate the complete recovery index to every participating drive. Payloads
                // remain single-copy for capacity; an index on any one drive is enough to discover
                // the snapshot and explain which other accounts are required.
                for (drive in drives) {
                    try {
                        val reference = drive.upload(temporary, "db-$id-${CloudManifest.FILE_NAME}")
                        if (reference.isBlank()) throw IOException("Provider did not confirm index upload")
                        copies[drive.accountId] = reference
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        missing += drive.accountId
                    }
                }
                if (copies.isEmpty()) throw IOException("Cloud backup has no recoverable index")
                Published(id, copies, missing)
            } finally {
                temporary.delete()
            }
        }
    }

    data class RetrievalResult(val folder: File, val unavailable: List<CloudArchive>)

    /** Downloads available verified files to a new tree and reports archives that could not be retrieved. */
    suspend fun retrieveAvailable(manifest: ByteArray, onProgress: (Int, Int) -> Unit = { _, _ -> }): RetrievalResult = withContext(Dispatchers.IO) {
        val archives = CloudManifest.decode(manifest)
        require(archives.isNotEmpty()) { "No archives are available for restore" }
        val staging = File(privateStaging, "snapshot-${UUID.randomUUID()}")
        check(privateStaging.isDirectory || privateStaging.mkdirs())
        check(staging.mkdirs())
        val transfer = CloudTransfer(drives)
        try {
            onProgress(0, archives.size)
            val slots = Semaphore(3)
            val progressLock = Any()
            var completed = 0
            val unavailable = java.util.Collections.synchronizedList(mutableListOf<CloudArchive>())
            coroutineScope {
                archives.map { archive ->
                    async {
                        slots.withPermit {
                            try {
                                transfer.retrieve(archive, staging)
                            } catch (error: CancellationException) {
                                throw error
                            } catch (_: Exception) {
                                unavailable += archive
                            }
                            synchronized(progressLock) { onProgress(++completed, archives.size) }
                        }
                    }
                }.awaitAll()
            }
            RetrievalResult(staging, unavailable.toList())
        } catch (error: Exception) {
            staging.deleteRecursively()
            throw error
        }
    }

    suspend fun retrieve(manifest: ByteArray, onProgress: (Int, Int) -> Unit = { _, _ -> }): File =
        retrieveAvailable(manifest, onProgress).folder
}
