package com.xayah.core.data.cloudpool

import java.io.File
import java.io.IOException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Encrypts finalized backup files before any provider can read them. */
internal object CloudPayload {
    private val random = SecureRandom()
    private val magic = "DBPAY001".toByteArray(Charsets.US_ASCII)
    private const val saltSize = 16
    private const val nonceSize = 12
    private const val tagSize = 16
    private const val chunkSize = 1024 * 1024
    private const val rounds = 210_000

    fun encrypt(source: File, destination: File, passphrase: CharArray, path: String) {
        val salt = ByteArray(saltSize).also(random::nextBytes)
        val baseNonce = ByteArray(nonceSize).also(random::nextBytes)
        val key = derive(passphrase, salt)
        try {
            source.inputStream().buffered().use { input ->
                java.io.DataOutputStream(destination.outputStream().buffered()).use { output ->
                    output.write(magic)
                    output.write(salt)
                    output.write(baseNonce)
                    val buffer = ByteArray(chunkSize)
                    var index = 0L
                    try {
                        while (true) {
                            val count = readChunk(input, buffer)
                            // A separately authenticated empty terminator detects truncation at a chunk boundary.
                            output.writeInt(count)
                            output.write(cipher(Cipher.ENCRYPT_MODE, key, baseNonce, index++, path, count).doFinal(buffer, 0, count))
                            if (count == 0) break
                        }
                    } finally {
                        buffer.fill(0)
                    }
                }
            }
        } finally {
            key.fill(0)
        }
    }

    fun decrypt(source: File, destination: File, passphrase: CharArray, path: String) {
        java.io.DataInputStream(source.inputStream().buffered()).use { input ->
            val header = ByteArray(magic.size).also(input::readFully)
            if (!header.contentEquals(magic)) throw IOException("Unsupported cloud archive format")
            val salt = ByteArray(saltSize).also(input::readFully)
            val baseNonce = ByteArray(nonceSize).also(input::readFully)
            val key = derive(passphrase, salt)
            try {
                destination.outputStream().buffered().use { output ->
                    var index = 0L
                    while (true) {
                        val count = input.readInt()
                        if (count !in 0..chunkSize) throw IOException("Invalid cloud archive chunk size")
                        val ciphertext = ByteArray(count + tagSize).also(input::readFully)
                        val plain = cipher(Cipher.DECRYPT_MODE, key, baseNonce, index++, path, count).doFinal(ciphertext)
                        try { output.write(plain) } finally { plain.fill(0) }
                        if (count == 0) {
                            if (input.read() != -1) throw IOException("Unexpected trailing cloud archive data")
                            break
                        }
                    }
                }
            } finally {
                key.fill(0)
            }
        }
    }

    private fun readChunk(input: java.io.InputStream, buffer: ByteArray): Int {
        var count = 0
        while (count < buffer.size) {
            val read = input.read(buffer, count, buffer.size - count)
            if (read < 0) break
            count += read
        }
        return count
    }

    private fun derive(passphrase: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(passphrase, salt, rounds, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    }

    private fun cipher(mode: Int, key: ByteArray, baseNonce: ByteArray, index: Long, path: String, count: Int): Cipher {
        val nonce = baseNonce.clone()
        for (i in 0 until Long.SIZE_BYTES) {
            val position = nonce.lastIndex - i
            nonce[position] = (nonce[position].toInt() xor (index ushr (i * 8)).toInt()).toByte()
        }
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            updateAAD(magic)
            updateAAD(java.nio.ByteBuffer.allocate(12).putLong(index).putInt(count).array())
            updateAAD(path.toByteArray(Charsets.UTF_8))
        }
    }
}
