package com.xayah.core.data.cloudpool

import android.content.Context
import com.yunx.app.data.network.GopeedRestore
import com.yunx.app.data.network.PersonalDrive
import java.io.File
import java.io.IOException

/** One authenticated account and its dedicated backup directory. */
class YunxCloudDrive(
    context: Context,
    account: ImportedCloudAccount,
    override val accountId: String,
    private val directoryId: String,
    private val connections: Int,
) : CloudDrive {
    private val drive = PersonalDrive(context, account.platform, account.credentialFields())
    private val gopeed = GopeedRestore(context)

    init {
        require(directoryId != drive.rootId && directoryId.isNotBlank())
        require(connections in 1..32)
    }

    suspend fun target(priority: Int, measuredBytesPerSecond: Long?): CloudTarget {
        val quota = drive.quota()
        val free = quota?.let { if (it.total <= 0 || it.used < 0) null else (it.total - it.used).coerceAtLeast(0) }
        return CloudTarget(accountId, priority, measuredBytesPerSecond, free, drive.maxFileBytes)
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

    override suspend fun download(fileId: String, destination: File) {
        val file = drive.list(directoryId).singleOrNull { it.fid == fileId && !it.isdir }
            ?: throw IOException("Archive is missing in its cloud directory")
        val link = drive.downloadLink(file)
        gopeed.download(link.downloadUrl, drive.downloadHeaders(link), destination, connections)
    }
}
