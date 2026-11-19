package com.xayah.core.data.cloudpool

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import java.io.File
import java.io.FileNotFoundException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores imported sessions only in local no-backup storage; never export this device-bound vault. */
class CloudAccountVault(context: Context) {
    private val file = AtomicFile(File(context.applicationContext.noBackupFilesDir, "cloud-accounts.v1"))

    fun save(accounts: List<ImportedCloudAccount>) = synchronized(lock) {
        val plain = gson.toJson(accounts.map { Record(it.platform, it.nickname, it.credentialFields()) }).toByteArray(Charsets.UTF_8)
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
            check(cipher.iv.size == IV_BYTES)
            val data = cipher.iv + cipher.doFinal(plain)
            require(data.size <= MAX_BYTES) { "Too many cloud credentials" }
            val stream = file.startWrite()
            try {
                stream.write(data)
                file.finishWrite(stream)
            } catch (error: Exception) {
                file.failWrite(stream)
                throw error
            }
        } finally {
            plain.fill(0)
        }
    }

    fun load(): List<ImportedCloudAccount> = synchronized(lock) {
        val stream = try {
            file.openRead()
        } catch (error: FileNotFoundException) {
            if (file.baseFile.exists()) throw error
            return@synchronized emptyList()
        }
        val encrypted = stream.use { input ->
            val buffer = ByteArray(MAX_BYTES + 1)
            var count = 0
            while (count < buffer.size) {
                val read = input.read(buffer, count, buffer.size - count)
                if (read < 0) break
                count += read
            }
            require(count in IV_BYTES + 16..MAX_BYTES) { "Invalid cloud credential vault" }
            buffer.copyOf(count)
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(create = false), GCMParameterSpec(128, encrypted, 0, IV_BYTES))
        val plain = cipher.doFinal(encrypted, IV_BYTES, encrypted.size - IV_BYTES)
        try {
            val type = TypeToken.getParameterized(List::class.java, Record::class.java).type
            gson.fromJson<List<Record>>(String(plain, Charsets.UTF_8), type).map {
                ImportedCloudAccount(it.platform, it.nickname, it.fields)
            }
        } finally {
            plain.fill(0)
        }
    }

    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        check(create) { "Cloud credentials must be imported again on this device" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
            generateKey()
        }
    }

    private class Record(
        @SerializedName("platform") val platform: String,
        @SerializedName("nickname") val nickname: String,
        @SerializedName("fields") val fields: Map<String, String>,
    )

    private companion object {
        val lock = Any()
        val gson = Gson()
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "databackup-cloud-accounts-v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val MAX_BYTES = 4 * 1024 * 1024
    }
}
