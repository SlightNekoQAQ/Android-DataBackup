package com.yunx.app.data.network

import android.content.Context
import com.yunx.app.data.network.model.DownloadLink
import com.yunx.app.data.network.model.QuotaInfo
import com.yunx.app.data.network.model.ShareFile
import com.yunx.app.data.upload.UploadIo
import com.yunx.app.data.upload.UploadSource
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Only the application supplies the credentials. No diagnostic or persistence responsibilities. */
class PersonalDrive(context: Context, val platform: String, fields: Map<String, String>) {
    private val resolver = context.applicationContext.contentResolver
    private val credentials = fields.toMap()
    private fun field(name: String) = credentials[name].orEmpty()
    private fun required(name: String) = field(name).ifBlank { throw IOException("Account session requires re-import") }
    private val cookie get() = required("cookie")
    private val token get() = required("accessToken")
    private val c139Cookie get() = if (C139Constants.extractAuthorization(cookie) != null) cookie
        else "$cookie; authorization=${required("authorization")}"

    private val quark by lazy { QuarkApi() }
    private val uc by lazy { UCApi() }
    private val baidu by lazy { BaiduApi() }
    private val c139 by lazy { C139Api(deviceInfoProvider = { C139DeviceInfo.from(context.applicationContext) }) }
    private val pan123 by lazy { Pan123DeviceId.install(context.applicationContext); Pan123Api() }
    private val pan115 by lazy { Pan115Api() }

    val rootId: String get() = when (platform) {
        "baidu", "c139" -> "/"
        else -> "0"
    }

    fun directoryId(file: ShareFile): String {
        require(file.isdir)
        return when (platform) {
            "baidu" -> file.fidToken
            else -> file.fid
        }.also { require(it.isNotBlank()) }
    }

    suspend fun list(parent: String): List<ShareFile> = withContext(Dispatchers.IO) {
        when (platform) {
            "quark" -> quark.listCloudFiles(parent, cookie).orEmpty()
            "uc" -> uc.listCloudFiles(parent, cookie).orEmpty()
            "baidu" -> baidu.listCloudFiles(parent, cookie)
            "c139" -> c139.listCloudFiles(parent, c139Cookie)
            "pan123" -> pan123.listCloudFiles(parent, token)
            "pan115" -> {
                val all = mutableListOf<ShareFile>()
                var offset = 0
                repeat(500) {
                    val page = pan115.listFiles(parent, cookie, offset)
                    all += page.files
                    offset += page.files.size
                    if (offset >= page.total || page.files.isEmpty()) return@withContext all
                }
                throw IOException("网盘目录分页未完成")
            }
            else -> unsupported()
        }
    }

    val maxFileBytes: Long? get() = if (platform == "pan115") Pan115Constants.UPLOAD_MAX_SIZE else null

    suspend fun quota(): QuotaInfo? = when (platform) {
        "quark" -> quark.getQuota(cookie)
        "uc" -> uc.getQuota(cookie)
        "baidu" -> baidu.getQuota(cookie)
        "c139" -> c139.getQuota(c139Cookie)
        "pan123" -> pan123.getQuota(token)
        "pan115" -> pan115.getQuota(cookie)
        else -> unsupported()
    }

    suspend fun createDirectory(parent: String, name: String): String {
        require(name.isNotBlank() && '/' !in name && '\\' !in name && name != "." && name != "..")
        return when (platform) {
            "quark" -> quark.createFolder(name, parent, cookie)
            "uc" -> uc.createFolder(name, parent, cookie)
            "baidu" -> "${parent.trimEnd('/')}/$name".takeIf { baidu.createDir(it, cookie) }
            "c139" -> c139.createDir(parent, name, c139Cookie)
            "pan123" -> pan123.createDir(parent, name, token)
            "pan115" -> pan115.createDir(parent, name, cookie)
            else -> unsupported()
        }?.takeIf(String::isNotBlank) ?: throw IOException("无法创建网盘备份目录")
    }

    suspend fun upload(parent: String, archive: File, remoteName: String): String {
        val source = UploadSource.fromArchive(archive, remoteName)
        val io = UploadIo(resolver, source, HttpClients::apiClient) { _, _ -> }
        val returned = when (platform) {
            "quark" -> quark.upload(io, source, parent, cookie)
            "uc" -> uc.upload(io, source, parent, cookie)
            "baidu" -> baidu.upload(io, source, parent, cookie)
            "c139" -> c139.upload(io, source, parent, c139Cookie)
            "pan123" -> pan123.upload(io, source, parent, token)
            "pan115" -> pan115.upload(io, source, parent, cookie)
            else -> unsupported()
        }
        // Some APIs return an upload-session ID or nothing (e.g. when deduplicated). Always confirm
        // the remote object by its unique name in the personal directory, not by an upload ACK alone.
        repeat(4) { attempt ->
            if (attempt != 0) kotlinx.coroutines.delay(500L * attempt)
            val files = list(parent).filter { !it.isdir && it.fsize == source.size }
            val exact = files.singleOrNull { it.fname == remoteName }
            if (exact != null) return exact.fid
            if (!returned.isNullOrBlank()) {
                files.singleOrNull { it.fid == returned }?.let { return it.fid }
            }
        }
        throw IOException("上传已返回，但未能确认备份文件存在")
    }

    /** Does not create a public share for private backup contents. */
    suspend fun downloadLink(file: ShareFile): DownloadLink {
        require(!file.isdir)
        return when (platform) {
            "quark" -> quark.getDownloadLink(file.fid, cookie)
            "uc" -> uc.cloudGetDownloadLink(file.fid, cookie)
            "baidu" -> DownloadLink(file.fid, file.fname, baidu.locateDownload(file.fidToken, cookie), file.fsize)
            "c139" -> c139.getDownloadUrl(file.fid, c139Cookie)
            "pan123" -> pan123.getDownloadLink(file, token)
            "pan115" -> pan115.getDownloadLink(file, cookie)
            else -> unsupported()
        }?.takeIf { it.downloadUrl.startsWith("https://") && !it.isHls }
            ?: throw IOException("网盘未返回可用的原始文件下载地址")
    }

    fun downloadHeaders(link: DownloadLink): Map<String, String> = when (platform) {
        "quark" -> mapOf("Cookie" to cookie, "User-Agent" to QuarkConstants.API_USER_AGENT, "Referer" to QuarkConstants.DOWNLOAD_REFERER)
        "uc" -> mapOf("Cookie" to cookie, "User-Agent" to UCConstants.USER_AGENT, "Referer" to UCConstants.DOWNLOAD_REFERER, "Origin" to UCConstants.WEB_ORIGIN)
        "baidu" -> mapOf("Cookie" to cookie, "User-Agent" to BaiduConstants.UA_NETDISK)
        "c139" -> mapOf("User-Agent" to C139Constants.PC_UA, "Referer" to "https://yun.139.com/")
        "pan123" -> mapOf("User-Agent" to Pan123Constants.WEB_UA, "Referer" to Pan123Constants.DOWNLOAD_REFERER)
        "pan115" -> mapOf("Cookie" to Pan115Constants.mergeCookies(cookie, link.guestCookie),
            "User-Agent" to Pan115Constants.CLIENT_UA, "Referer" to Pan115Constants.DOWNLOAD_REFERER)
        else -> unsupported()
    }

    private fun unsupported(): Nothing = throw IOException("Unsupported cloud provider")
}
