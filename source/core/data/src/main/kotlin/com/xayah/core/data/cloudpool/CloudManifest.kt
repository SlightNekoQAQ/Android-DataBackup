package com.xayah.core.data.cloudpool

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

/**
 * Portable cloud index. It is stored in the clear on purpose: recovery must not depend on a
 * separate passphrase typed by the user, only on the cloud account that already holds the data.
 * It contains no credentials and no signed download URLs.
 */
object CloudManifest {
    const val FILE_NAME = "databackup-manifest-v1.json"
    private const val FORMAT = "databackup-cloud-index"
    private const val VERSION = 1
    private const val maximumSize = 8 * 1024 * 1024
    private val gson = Gson()

    fun encode(archives: List<CloudArchive>): ByteArray {
        validate(archives)
        val bytes = gson.toJson(Document(FORMAT, VERSION, archives.map(::WireArchive))).toByteArray(Charsets.UTF_8)
        require(bytes.size <= maximumSize) { "Cloud backup index is too large" }
        return bytes
    }

    fun decode(encoded: ByteArray): List<CloudArchive> {
        require(encoded.size in 2..maximumSize) { "Invalid cloud backup manifest" }
        val document = try {
            gson.fromJson(String(encoded, Charsets.UTF_8), Document::class.java)
        } catch (_: RuntimeException) {
            throw IllegalArgumentException("Invalid cloud backup manifest")
        } ?: throw IllegalArgumentException("Invalid cloud backup manifest")
        require(document.format == FORMAT && document.version == VERSION) { "Unsupported cloud backup manifest" }
        return document.items.map { it.toArchive() }.also(::validate)
    }

    private fun validate(archives: List<CloudArchive>) {
        require(archives.isNotEmpty() && archives.map(CloudArchive::relativePath).distinct().size == archives.size)
        archives.forEach {
            validateRelativePath(it.relativePath)
            require(it.accountId.isNotBlank() && it.fileId.isNotBlank() && it.byteCount >= 0)
            require(it.sha256.matches(Regex("[0-9a-f]{64}")))
        }
    }

    private class Document(
        @SerializedName("format") val format: String,
        @SerializedName("version") val version: Int,
        @SerializedName("items") val items: List<WireArchive>,
    )

    private class WireArchive(
        @SerializedName("path") val path: String,
        @SerializedName("account") val account: String,
        @SerializedName("object") val remoteObject: String,
        @SerializedName("bytes") val bytes: Long,
        @SerializedName("sha256") val sha256: String,
    ) {
        constructor(archive: CloudArchive) : this(archive.relativePath, archive.accountId, archive.fileId, archive.byteCount, archive.sha256)
        fun toArchive() = CloudArchive(path, account, remoteObject, bytes, sha256)
    }
}
