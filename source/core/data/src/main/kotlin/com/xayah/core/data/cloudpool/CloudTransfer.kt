package com.xayah.core.data.cloudpool

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale

/** The driver must return a durable, private file reference after the provider commits the upload. */
interface CloudDrive {
    val accountId: String
    suspend fun listObjects(): List<CloudRecovery.RemoteObject>
    suspend fun upload(source: File, remoteName: String): String
    suspend fun download(fileId: String, destination: File)
}

/** Explicit provider quota or object-size rejection, not a generic network/authorization error. */
class CloudCapacityException(message: String) : IOException(message)

/** Portable manifest entry. Contains no authorization headers or download URLs. */
data class CloudArchive(
    val relativePath: String,
    val accountId: String,
    val fileId: String,
    val byteCount: Long,
    val sha256: String,
)

/** One sequential writer per backup session. Archive sources must be finalized and immutable. */
class CloudTransfer(drives: Collection<CloudDrive>) {
    private val drivers = drives.associateBy(CloudDrive::accountId)
    private val sessionBytes = mutableMapOf<String, Long>()

    init {
        require(drivers.size == drives.size) { "Duplicate cloud account" }
    }

    /** target quotas are snapshots taken before this session, not updated after each upload. */
    suspend fun store(
        source: File,
        relativePath: String,
        remoteName: String,
        targets: List<CloudTarget>,
        useManualOrder: Boolean = false,
    ): CloudArchive {
        require(source.isFile)
        validateRelativePath(relativePath)
        validateRelativePath(remoteName)
        require('/' !in remoteName)
        val size = source.length()
        val digest = source.inputStream().use(::sha256)
        val candidates = CloudPlacement.candidates(
            size, targets.filter { it.accountId in drivers }, sessionBytes, useManualOrder = useManualOrder,
        )
        for (target in candidates) {
            val used = sessionBytes[target.accountId] ?: 0
            val newTotal = Math.addExact(used, size)
            val id = try {
                drivers.getValue(target.accountId).upload(source, remoteName)
            } catch (_: CloudCapacityException) {
                continue
            }
            check(id.isNotBlank()) { "Upload returned no file reference" }
            sessionBytes[target.accountId] = newTotal
            return CloudArchive(relativePath, target.accountId, id, size, digest)
        }
        throw CloudCapacityException("No writable cloud account can fit this archive")
    }

    /** Restore to a fresh private staging tree before handing verified archives to DataBackup. */
    suspend fun retrieve(archive: CloudArchive, staging: File): File {
        validateRelativePath(archive.relativePath)
        require(archive.byteCount >= 0 && archive.sha256.matches(Regex("[0-9a-f]{64}")))
        val driver = drivers[archive.accountId] ?: throw IOException("Required cloud account is not available")
        val root = staging.canonicalFile
        val destination = File(root, archive.relativePath).canonicalFile
        require(destination.path.startsWith(root.path.trimEnd('/') + '/'))
        if (destination.exists()) throw IOException("Archive already exists in restore staging")
        val directory = checkNotNull(destination.parentFile)
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) throw IOException("Cannot create restore directory")
        val partial = File.createTempFile("cloud-", ".partial", directory)
        try {
            driver.download(archive.fileId, partial)
            if (partial.length() != archive.byteCount || partial.inputStream().use(::sha256) != archive.sha256) {
                throw IOException("Downloaded backup failed integrity verification")
            }
            if (!partial.renameTo(destination)) throw IOException("Cannot commit downloaded archive")
            return destination
        } finally {
            partial.delete()
        }
    }
}

internal fun validateRelativePath(path: String) {
    require('\\' !in path && '\u0000' !in path && path.split('/').none { it.isBlank() || it == "." || it == ".." }) {
        "Invalid archive path"
    }
}

private fun sha256(input: InputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        digest.update(buffer, 0, read)
    }
    return digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
}
