package com.yunx.app.data.network

import android.content.Context
import com.gopeed.libgopeed.InvokeResultListener
import com.gopeed.libgopeed.Libgopeed
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** In-process Gopeed; no HTTP listener is opened. A restore writes into private staging. */
class GopeedRestore(context: Context) {
    private val context = context.applicationContext

    suspend fun download(
        url: String,
        headers: Map<String, String>,
        destination: File,
        connections: Int,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ) = withContext(Dispatchers.IO) {
        taskMutex.withLock {
        require(url.startsWith("https://"))
        require(connections in 1..32)
        val root = context.noBackupFilesDir
        val parent = checkNotNull(destination.canonicalFile.parentFile)
        require(parent.path.startsWith(root.canonicalPath + File.separator)) { "Use private restore staging" }
        check(parent.isDirectory || parent.mkdirs())
        start(root)
        val name = "restore-${UUID.randomUUID()}.part"
        val temporary = File(parent, name)
        var id: String? = null
        try {
            val request = JSONObject().put("url", url).put("extra", JSONObject().put("header", JSONObject(headers)))
            val options = JSONObject().put("path", parent.path).put("name", name)
                .put("extra", JSONObject().put("connections", connections))
            val task = call("POST", "/api/v1/tasks", JSONObject().put("req", request).put("opts", options)).optString("data")
            if (!task.matches(Regex("[A-Za-z0-9_-]{1,128}"))) throw IOException("Gopeed did not create a restore task")
            id = task
            val path = "/api/v1/tasks/$task"
            var previousBytes = -1L
            var lastProgress = android.os.SystemClock.elapsedRealtime()
            while (true) {
                val status = call("GET", "$path/status").optJSONObject("data") ?: throw IOException("Missing Gopeed status")
                val downloaded = status.optLong("downloaded")
                onProgress(downloaded, status.optLong("total"))
                if (downloaded != previousBytes) {
                    previousBytes = downloaded
                    lastProgress = android.os.SystemClock.elapsedRealtime()
                } else if (android.os.SystemClock.elapsedRealtime() - lastProgress > 180_000L) {
                    throw IOException("Gopeed download stopped making progress")
                }
                when (status.optString("status")) {
                    "done" -> break
                    "error", "paused" -> throw IOException("Gopeed could not download the backup")
                }
                delay(400)
            }
            if (!temporary.isFile || !temporary.renameTo(destination)) throw IOException("Cannot stage Gopeed download")
        } finally {
            withContext(NonCancellable) {
                id?.let { runCatching { call("DELETE", "/api/v1/tasks/$it") } }
                temporary.delete()
            }
        }
        }
    }

    private companion object {
        private val taskMutex = Mutex()
        private val sequence = AtomicLong()
        private var running = false

        @Synchronized
        fun start(root: File) {
            if (running) return
            try {
                System.loadLibrary("gojni")
            } catch (_: UnsatisfiedLinkError) {
                throw IOException("Gopeed kernel is unavailable for this device")
            }
            val store = File(root, "gopeed-store")
            val temp = File(root, "gopeed-tmp")
            // Even in memory mode Gopeed creates logs under storageDir. An omitted
            // directory makes it try to create ./logs in Android's read-only cwd.
            for (dir in listOf(store, File(store, "logs"), temp)) {
                if ((!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) || !dir.canWrite()) {
                    throw IOException("Cannot prepare private Gopeed directories")
                }
            }
            // Credentials can appear in request metadata; do not persist a Bolt task database.
            val config = JSONObject().put("storage", "mem")
                .put("storageDir", store.absolutePath + File.separator)
                .put("tempDir", temp.absolutePath + File.separator)
                .put("apiEnable", false).put("refreshInterval", 500)
                .put("downloadConfig", JSONObject().put("downloadDir", temp.path).put("maxRunning", 1))
            Libgopeed.start(config.toString())
            running = true
        }

        fun call(method: String, path: String, body: JSONObject? = null): JSONObject {
            val latch = CountDownLatch(1)
            var result: String? = null
            Libgopeed.invokeAsync(method, path, "", body?.toString().orEmpty(), sequence.incrementAndGet(),
                object : InvokeResultListener {
                    override fun onResult(id: Long, success: Boolean, data: String?) {
                        if (success) result = data
                        latch.countDown()
                    }
                })
            if (!latch.await(60, TimeUnit.SECONDS)) throw IOException("Gopeed request timed out")
            val text = result ?: throw IOException("Gopeed request failed")
            val json = try { JSONObject(text) } catch (_: Exception) { throw IOException("Invalid Gopeed response") }
            if (json.optInt("code") != 0) throw IOException("Gopeed rejected the request")
            return json
        }
    }
}
