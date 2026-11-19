package com.yunx.app.data.network

import android.content.Context
import com.yunx.app.data.network.model.DownloadLink
import com.yunx.app.data.network.model.ShareFile
import com.yunx.app.data.upload.UploadIo
import com.yunx.app.data.upload.UploadSource
import java.io.File
import java.io.IOException

/** A private-directory transport for the imported personal account, not the VIP WebDAV API. */
class Pan123BackupTransport(context: Context, private val token: String, private val directoryId: String) {
    private val resolver = context.applicationContext.contentResolver
    private val api = run {
        Pan123DeviceId.install(context.applicationContext)
        Pan123Api()
    }

    init {
        require(token.isNotBlank())
        require(directoryId.toLongOrNull()?.let { it > 0 } == true) { "Select a dedicated backup directory" }
    }

    suspend fun list(): List<ShareFile> = api.listCloudFiles(directoryId, token)

    suspend fun freeBytes(): Long? = api.getQuota(token)?.let { quota ->
        if (quota.total <= 0 || quota.used < 0) null else (quota.total - quota.used).coerceAtLeast(0)
    }

    suspend fun upload(source: File, remoteName: String): String {
        val file = UploadSource.fromArchive(source, remoteName)
        val io = UploadIo(resolver, file, HttpClients::apiClient) { _, _ -> }
        return api.upload(io, file, directoryId, token)
    }

    /** Resolve a fresh link from the private listing when restoring, never persist a signed URL. */
    suspend fun resolveDownload(fileId: String): DownloadLink {
        val file = list().singleOrNull { it.fid == fileId && !it.isdir }
            ?: throw IOException("Backup file is not present in the selected 123 directory")
        return api.getDownloadLink(file, token) ?: throw IOException("123 did not provide a download link")
    }
}
