package com.xayah.core.data.cloudpool

import android.util.Base64
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Credentials are deliberately excluded from the object's string representation. */
class ImportedCloudAccount internal constructor(val platform: String, val nickname: String, fields: Map<String, String>) {
    private val credentials = fields.toMap()
    fun credential(name: String): String? = credentials[name]
    internal fun credentialFields(): Map<String, String> = credentials.toMap()
    override fun toString(): String = "ImportedCloudAccount(platform=$platform)"
}

/** Reads YunX v1.3.1's user-selected export, not YunX's application data. No storage or logging. */
object YunxCredentialImport {
    private const val MAX_EXPORT_CHARS = 4 * 1024 * 1024
    private const val MAGIC_BYTES = 12
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BYTES = 16
    private data class Schema(val required: String, val fields: Set<String>)
    private fun schema(primary: String, vararg extra: String) = Schema(primary, setOf(primary, *extra))

    private val schemas = mapOf(
        "quark" to schema("cookie"),
        "uc" to schema("cookie"),
        "xunlei" to schema("accessToken", "refreshToken", "deviceId", "captchaToken", "authType"),
        "baidu" to schema("cookie"),
        "c139" to schema("cookie", "authorization"),
        "pan123" to schema("accessToken", "account"),
        "pan115" to schema("cookie"),
        "guangya" to schema("accessToken", "refreshToken", "deviceId", "deviceSign", "account"),
        "ilanzou" to schema("appToken", "uuid", "account", "password", "userId"),
        "lanzou" to schema("cookie"),
    )

    fun parse(content: String, password: CharArray? = null): List<ImportedCloudAccount> {
        require(content.length <= MAX_EXPORT_CHARS) { "YunX export is too large" }
        val trimmed = content.trim().removePrefix("\uFEFF").trimStart()
        val json = if (trimmed.startsWith('{')) trimmed else decrypt(trimmed, password)
        return try {
            val root = JsonParser.parseString(json).asJsonObject
            require(root.text("app") == "yunx_auth_backup" && root.text("version") == "1")
            root.getAsJsonArray("accounts").mapNotNull { entry ->
                val item = entry.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val platform = item.text("platform") ?: return@mapNotNull null
                val schema = schemas[platform] ?: return@mapNotNull null
                if (item.text(schema.required).isNullOrBlank()) return@mapNotNull null
                ImportedCloudAccount(platform, item.text("nickname").orEmpty(), schema.fields.mapNotNull { field ->
                    item.text(field)?.let { field to it }
                }.toMap())
            }
        } catch (_: RuntimeException) {
            // Parser exceptions can contain fragments of the input. Do not expose them.
            throw IllegalArgumentException("Invalid YunX credential export")
        }
    }

    private fun JsonObject.text(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive }?.asString

    private fun decrypt(content: String, password: CharArray?): String {
        require(password != null && password.isNotEmpty()) { "YunX export password is required" }
        val payload = try {
            Base64.decode(content, Base64.DEFAULT)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid YunX credential export")
        }
        try {
            val saltEnd = MAGIC_BYTES + SALT_BYTES
            val ivEnd = saltEnd + IV_BYTES
            require(payload.size >= ivEnd + TAG_BYTES) { "Invalid YunX credential export" }
            val iterations = when (String(payload, 0, MAGIC_BYTES, Charsets.US_ASCII)) {
                "YUNX_AUTH_V1" -> 10_000
                "YUNX_AUTH_V2" -> 210_000
                else -> throw IllegalArgumentException("Unsupported YunX export encryption")
            }
            val spec = PBEKeySpec(password, payload.copyOfRange(MAGIC_BYTES, saltEnd), iterations, 256)
            val key = try {
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            } finally {
                spec.clearPassword()
            }
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, payload, saltEnd, IV_BYTES))
                val plain = try {
                    cipher.doFinal(payload, ivEnd, payload.size - ivEnd)
                } catch (_: java.security.GeneralSecurityException) {
                    throw IllegalArgumentException("Wrong YunX password or damaged export")
                }
                return try { String(plain, Charsets.UTF_8) } finally { plain.fill(0) }
            } finally {
                key.fill(0)
            }
        } finally {
            payload.fill(0)
        }
    }
}
