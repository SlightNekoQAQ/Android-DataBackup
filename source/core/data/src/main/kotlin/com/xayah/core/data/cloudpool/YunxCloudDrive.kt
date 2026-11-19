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
        val quota = if (drive.supportsPrivateRestore) drive.quota() else null
        val free = quota?.let { if (it.total <= 0 || it.used < 0) null else (it.total - it.used).coerceAtLeast(0) }
        return CloudTarget(accountId, priority, measuredBytesPerSecond, free, drive.maxFileBytes, drive.supportsPrivateRestore)
    }

    override suspend fun upload(source: File, remoteName: String): String {
        check(drive.supportsPrivateRestore) { "Provider cannot privately restore backups" }
        val max = drive.maxFileBytes
        if (max != null && source.length() > max) throw CloudCapacityException("Archive exceeds provider size limit")
        return drive.upload(directoryId, source, remoteName)
    }

    override suspend fun download(fileId: String, destination: File) {
        val file = drive.list(directoryId).singleOrNull { it.fid == fileId && !it.isdir }
            ?: throw IOException("Archive is missing in its cloud directory")
        val link = drive.downloadLink(file)
        gopeed.download(link.downloadUrl, drive.downloadHeaders(link), destination, connections)
    }
}
