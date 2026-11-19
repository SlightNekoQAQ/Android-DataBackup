package com.xayah.core.data.cloudpool

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

/**
 * Portable cloud index. It is stored in the clear on purpose: recovery must not depend on a
 * separate passphrase typed by the user, only on the cloud account that already holds the data.
 * It contains no credentials and no signed download URLs.
 */
object CloudManifest {
    const val FILE_NAME = "databackup-manifest-v3.json"
    private const val FORMAT = "databackup-cloud-index"
    private const val VERSION = 3
    private const val maximumSize = 8 * 1024 * 1024
    private val gson = Gson()

    data class BackupEntry(val path: String, val kind: String, val displayName: String, val archive: String)

    data class Contents(val archives: List<CloudArchive>, val entries: List<BackupEntry>)

    fun encode(archives: List<CloudArchive>, entries: List<BackupEntry> = emptyList()): ByteArray {
        validate(archives)
        entries.forEach {
            validateRelativePath(it.path)
            validateRelativePath(it.archive)
            require(it.kind in setOf("app", "file") && it.displayName.isNotBlank())
        }
        require(entries.map(BackupEntry::path).distinct().size == entries.size) { "Duplicate backup entry" }
        require(entries.all { entry -> archives.any { it.relativePath == entry.archive } }) { "Backup index entry references a missing archive" }
        val bytes = gson.toJson(Document(FORMAT, VERSION, archives.map(::WireArchive), entries.map(::WireEntry))).toByteArray(Charsets.UTF_8)
        require(bytes.size <= maximumSize) { "Cloud backup index is too large" }
        return bytes
    }

    fun decode(encoded: ByteArray): List<CloudArchive> = decodeContents(encoded).archives

    fun decodeContents(encoded: ByteArray): Contents {
        require(encoded.size in 2..maximumSize) { "Invalid cloud backup manifest" }
        val document = try {
            gson.fromJson(String(encoded, Charsets.UTF_8), Document::class.java)
        } catch (_: RuntimeException) {
            throw IllegalArgumentException("Invalid cloud backup manifest")
        } ?: throw IllegalArgumentException("Invalid cloud backup manifest")
        require(document.format == FORMAT && document.version == VERSION) { "Unsupported cloud backup manifest" }
        val archives = document.items.map { it.toArchive() }.also(::validate)
        val contents = document.entries.orEmpty().map { it.toEntry() }
        contents.forEach {
            validateRelativePath(it.path)
            validateRelativePath(it.archive)
            require(it.kind in setOf("app", "file") && it.displayName.isNotBlank())
        }
        require(contents.map(BackupEntry::path).distinct().size == contents.size) { "Duplicate backup entry" }
        require(contents.all { entry -> archives.any { it.relativePath == entry.archive } }) { "Backup index entry references a missing archive" }
        return Contents(archives, contents)
    }

    private fun validate(archives: List<CloudArchive>) {
        require(archives.isNotEmpty() && archives.map(CloudArchive::relativePath).distinct().size == archives.size)
        archives.forEach {
            validateRelativePath(it.relativePath)
            require(it.accountId.isNotBlank() && it.accountIdentity.isNotBlank() && it.fileId.isNotBlank() && it.byteCount >= 0)
            require(it.sha256.matches(Regex("[0-9a-f]{64}")))
        }
    }

    private class Document(
        @SerializedName("format") val format: String,
        @SerializedName("version") val version: Int,
        @SerializedName("items") val items: List<WireArchive>,
        @SerializedName("contents") val entries: List<WireEntry>?,
    )

    private class WireArchive(
        @SerializedName("path") val path: String,
        @SerializedName("account") val account: String,
        @SerializedName("identity") val identity: String,
        @SerializedName("object") val remoteObject: String,
        @SerializedName("bytes") val bytes: Long,
        @SerializedName("sha256") val sha256: String,
    ) {
        constructor(archive: CloudArchive) : this(archive.relativePath, archive.accountId, archive.accountIdentity, archive.fileId, archive.byteCount, archive.sha256)
        fun toArchive() = CloudArchive(path, account, remoteObject, bytes, sha256, identity)
    }

    private class WireEntry(
        @SerializedName("path") val path: String,
        @SerializedName("kind") val kind: String,
        @SerializedName("name") val name: String,
        @SerializedName("archive") val archive: String,
    ) {
        constructor(entry: BackupEntry) : this(entry.path, entry.kind, entry.displayName, entry.archive)
        fun toEntry() = BackupEntry(path, kind, name, archive)
    }
}
