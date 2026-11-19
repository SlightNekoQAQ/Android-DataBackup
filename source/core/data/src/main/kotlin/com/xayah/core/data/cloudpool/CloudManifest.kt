package com.xayah.core.data.cloudpool

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Authenticated cloud index. The caller uploads this only after all payloads are committed. */
object CloudManifest {
    const val FILE_NAME = "databackup-manifest-v1.bin"
    private val header = "DBPOOL01".toByteArray(Charsets.US_ASCII)
    private const val saltSize = 16
    private const val ivSize = 12
    private const val tagSize = 16
    private const val maximumSize = 8 * 1024 * 1024
    private val random = SecureRandom()
    private val gson = Gson()

    fun encode(archives: List<CloudArchive>, passphrase: CharArray): ByteArray {
        require(passphrase.size >= 8) { "Backup passphrase must contain at least 8 characters" }
        validate(archives)
        val plain = gson.toJson(archives.map(::WireArchive)).toByteArray(Charsets.UTF_8)
        try {
            require(plain.size <= maximumSize - header.size - saltSize - ivSize - tagSize)
            val salt = ByteArray(saltSize).also(random::nextBytes)
            val iv = ByteArray(ivSize).also(random::nextBytes)
            return header + salt + iv + cipher(Cipher.ENCRYPT_MODE, passphrase, salt, iv).doFinal(plain)
        } finally {
            plain.fill(0)
        }
    }

    fun decode(encoded: ByteArray, passphrase: CharArray): List<CloudArchive> {
        val saltEnd = header.size + saltSize
        val ivEnd = saltEnd + ivSize
        require(encoded.size in ivEnd + tagSize..maximumSize && encoded.copyOfRange(0, header.size).contentEquals(header)) {
            "Invalid cloud backup manifest"
        }
        val salt = encoded.copyOfRange(header.size, saltEnd)
        val iv = encoded.copyOfRange(saltEnd, ivEnd)
        val plain = try {
            cipher(Cipher.DECRYPT_MODE, passphrase, salt, iv).doFinal(encoded, ivEnd, encoded.size - ivEnd)
        } catch (_: GeneralSecurityException) {
            throw IllegalArgumentException("Incorrect backup passphrase or damaged manifest")
        }
        return try {
            gson.fromJson(String(plain, Charsets.UTF_8), Array<WireArchive>::class.java).map { it.toArchive() }.also(::validate)
        } catch (_: RuntimeException) {
            throw IllegalArgumentException("Invalid cloud backup manifest")
        } finally {
            plain.fill(0)
        }
    }

    private fun validate(archives: List<CloudArchive>) {
        require(archives.isNotEmpty() && archives.map(CloudArchive::relativePath).distinct().size == archives.size)
        archives.forEach {
            validateRelativePath(it.relativePath)
            require(it.accountId.isNotBlank() && it.fileId.isNotBlank() && it.byteCount >= 0)
            require(it.sha256.matches(Regex("[0-9a-f]{64}")))
        }
    }

    private fun cipher(mode: Int, passphrase: CharArray, salt: ByteArray, iv: ByteArray): Cipher {
        val spec = PBEKeySpec(passphrase, salt, 210_000, 256)
        val key = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
        try {
            return Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
                updateAAD(header)
            }
        } finally {
            key.fill(0)
        }
    }

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
