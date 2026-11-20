package com.xayah.core.data.cloudpool

import android.content.Context
import com.yunx.app.data.network.PersonalDrive
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One authenticated account and its dedicated backup directory. */
class YunxCloudDrive(
    context: Context,
    account: ImportedCloudAccount,
    override val accountId: String,
    private val directoryId: String,
    private val connections: Int,
) : CloudDrive {
    private val importedAccount = account
    private val driveContext = context.applicationContext
    /** Account identity returned by the provider API, consistent across devices. */
    suspend fun verifiedAccountIdentity(): String = importedAccount.verifiedIdentity(driveContext)
    private val drive = PersonalDrive(context, account.platform, account.credentialFields())

    init {
        require(directoryId.isNotBlank())
        require(connections in 1..32)
    }

    val maxFileBytes: Long? get() = drive.maxFileBytes

    /** Free bytes when the provider reports a usable quota; null means unknown, never "full". */
    suspend fun quotaSnapshot(): Long? {
        val quota = drive.quota() ?: return null
        if (quota.total <= 0 || quota.used < 0) return null
        return (quota.total - quota.used).coerceAtLeast(0)
    }

    override suspend fun upload(source: File, remoteName: String): String {
        val max = drive.maxFileBytes
        if (max != null && source.length() > max) throw CloudCapacityException("Archive exceeds provider size limit")
        return try {
            drive.upload(directoryId, source, remoteName)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            // Only explicit server quota/size rejections are eligible for failover. A lost ACK
            // or expired login may have committed a file already; don't silently write elsewhere.
            if (isCapacityRejection(error.message.orEmpty())) throw CloudCapacityException("Cloud capacity rejected the archive")
            throw error
        }
    }

    private fun isCapacityRejection(message: String): Boolean {
        val text = message.lowercase(java.util.Locale.ROOT)
        return listOf("storage quota exceeded", "insufficient storage", "file too large", "空间不足", "容量不足",
            "空间已满", "容量已满", "存储空间已用完", "超过单文件", "单文件最大", "文件大小超出").any(text::contains)
    }

    override suspend fun listObjects(): List<CloudRecovery.RemoteObject> = drive.list(directoryId)
        .filterNot { it.isdir }.map { CloudRecovery.RemoteObject(it.fid, it.fname, it.fsize) }

    override suspend fun download(fileId: String, destination: File) = withContext(Dispatchers.IO) {
        val file = drive.list(directoryId).singleOrNull { it.fid == fileId && !it.isdir }
            ?: throw IOException("Archive is missing in its cloud directory")
        val link = drive.downloadLink(file)
        val connection = (URL(link.downloadUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            drive.downloadHeaders(link).forEach { (name, value) -> setRequestProperty(name, value) }
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) throw IOException("网盘下载失败 (HTTP $status)")
            connection.inputStream.use { input ->
                destination.outputStream().buffered().use { output -> input.copyTo(output, 128 * 1024) }
            }
        } finally {
            connection.disconnect()
        }
    }
}
