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
    private val importedAccount = account
    private val platform = account.platform
    private val driveContext = context.applicationContext
    /** Account identity returned by the provider API, consistent across devices. */
    suspend fun verifiedAccountIdentity(): String = importedAccount.verifiedIdentity(driveContext)
    private val drive = PersonalDrive(context, account.platform, account.credentialFields())
    private val gopeed = GopeedRestore(context)

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

    override suspend fun download(fileId: String, destination: File) = download(fileId, destination, connections)

    override suspend fun downloadManifest(fileId: String, destination: File) = download(fileId, destination, 1)

    private suspend fun download(fileId: String, destination: File, connections: Int) {
        val file = drive.list(directoryId).singleOrNull { it.fid == fileId && !it.isdir }
            ?: throw IOException("Archive is missing in its cloud directory")
        suspend fun fetch(refreshSession: Boolean) {
            val request = drive.downloadRequest(file, refreshSession)
            gopeed.download(request.url, request.headers, destination, connections)
        }
        try {
            fetch(refreshSession = false)
        } catch (error: GopeedRestore.TaskFailed) {
            if (platform != "quark") throw error
            // Retry once with a fresh session AND a newly signed link; never mix accounts.
            fetch(refreshSession = true)
        }
    }
}
