package com.xayah.core.data.cloudpool

import java.io.File
import java.io.IOException
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudPoolTest {
    @get:Rule val temporary = TemporaryFolder()

    private val fast = CloudTarget("fast", 1, 10, 100, 70)
    private val slow = CloudTarget("slow", 0, 5, 150, null)

    @Test fun placesWholeFilesBySpeedUnlessSpaceOrFileLimitRulesOutFastest() {
        val targets = listOf(slow, fast)
        assertEquals(listOf(fast, slow), CloudPlacement.candidates(70, targets))
        assertEquals(listOf(slow), CloudPlacement.candidates(71, targets))
        assertEquals(listOf(slow), CloudPlacement.candidates(50, targets, mapOf("fast" to 51)))
        assertEquals(listOf(slow, fast), CloudPlacement.candidates(50, targets, useManualOrder = true))
        assertTrue(CloudPlacement.candidates(151, targets).isEmpty())
    }

    @Test fun fallsBackOnlyOnCapacityAndRecoversFromRecordedAccount() {
        val source = temporary.newFile().apply { writeText("backup") }
        val first = MemoryDrive("fast", CloudCapacityException("full"))
        val second = MemoryDrive("slow")
        val session = CloudTransfer(listOf(first, second))
        val archive = immediate { session.store(source, "apps/app/archive", "archive", listOf(slow, fast)) }
        assertEquals("slow", archive.accountId)
        assertEquals(1, first.uploads)
        assertEquals(1, second.uploads)
        val freshSession = CloudTransfer(listOf(second))
        val restored = immediate { freshSession.retrieve(archive, temporary.newFolder()) }
        assertEquals("backup", restored.readText())
        second.data = "broken".toByteArray()
        val corrupted = temporary.newFolder()
        assertThrows(IOException::class.java) { immediate { freshSession.retrieve(archive, corrupted) } }
        assertFalse(File(corrupted, archive.relativePath).exists())
        assertThrows(IllegalArgumentException::class.java) {
            immediate { freshSession.retrieve(archive.copy(relativePath = "../outside"), corrupted) }
        }
        val networkFailure = CloudTransfer(listOf(MemoryDrive("fast", IOException("offline")), second))
        assertThrows(IOException::class.java) {
            immediate { networkFailure.store(source, "archive", "archive", listOf(fast, slow)) }
        }
        assertEquals(1, second.uploads)
    }

    private class MemoryDrive(override val accountId: String, private val failure: IOException? = null) : CloudDrive {
        var data = byteArrayOf()
        var uploads = 0
        override suspend fun upload(source: File, remoteName: String): String {
            uploads++
            failure?.let { throw it }
            data = source.readBytes()
            return "private-file-id"
        }
        override suspend fun download(fileId: String, destination: File) {
            check(fileId == "private-file-id")
            destination.writeBytes(data)
        }
    }

    /** The fake drivers above never suspend; no coroutine runtime is needed for these tests. */
    private fun <T> immediate(block: suspend () -> T): T {
        var result: Result<T>? = null
        block.startCoroutine(object : Continuation<T> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(value: Result<T>) { result = value }
        })
        return checkNotNull(result).getOrThrow()
    }

    @Test fun parsesAllTenPlatformRecordsAndSkipsNonDriveCredentials() {
        val platforms = mapOf(
            "quark" to "cookie", "uc" to "cookie", "xunlei" to "accessToken", "baidu" to "cookie",
            "c139" to "cookie", "pan123" to "accessToken", "pan115" to "cookie", "guangya" to "accessToken",
            "ilanzou" to "appToken", "lanzou" to "cookie",
        )
        val entries = platforms.map { (platform, key) -> """{"platform":"$platform","$key":"example-secret"}""" } +
            listOf("""{"platform":"video_bilibili","cookie":"ignored"}""", """{"platform":"quark","cookie":""}""")
        val export = """{"app":"yunx_auth_backup","version":1,"githubToken":"ignored","accounts":[${entries.joinToString(",")}]}"""
        val accounts = YunxCredentialImport.parse(export)
        assertEquals(platforms.keys.toList(), accounts.map { it.platform })
        accounts.forEach {
            assertEquals("example-secret", it.credential(platforms.getValue(it.platform)))
            assertFalse(it.toString().contains("example-secret"))
            assertEquals(null, it.credential("githubToken"))
        }
    }
}
