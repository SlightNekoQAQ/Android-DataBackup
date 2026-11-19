package com.xayah.core.data.cloudpool

import java.io.File
import java.io.IOException
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlinx.coroutines.runBlocking
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
        second.objects[archive.fileId] = "broken".toByteArray()
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
        val objects = mutableMapOf<String, ByteArray>()
        var uploads = 0
        override suspend fun upload(source: File, remoteName: String): String {
            uploads++
            failure?.let { throw it }
            objects[remoteName] = source.readBytes()
            return remoteName
        }
        override suspend fun download(fileId: String, destination: File) {
            destination.writeBytes(objects.getValue(fileId))
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

    @Test fun publishesManifestToBothDisksAndRestoresWithoutOriginalFiles() = runBlocking {
        val first = MemoryDrive("fast")
        val second = MemoryDrive("slow")
        val passphrase = "test-passphrase".toCharArray()
        val snapshot = CloudSnapshot(listOf(first, second), temporary.newFolder())
        // Prefer a deliberately slower drive by the user's order; it fits exactly one encrypted archive.
        val writer = snapshot.begin(listOf(fast.copy(freeBytes = 150, maxFileBytes = null, priority = 1),
            slow.copy(freeBytes = 81, priority = 0)), passphrase, useManualOrder = true)
        val a = temporary.newFile().apply { writeText("first") }
        writer.append(CloudSnapshot.Source("apps/a/archive", a), deleteAfterUpload = true)
        assertFalse(a.exists())
        assertEquals(1, second.objects.size)
        assertTrue(first.objects.isEmpty())
        val b = temporary.newFile().apply { writeText("second") }
        writer.append(CloudSnapshot.Source("apps/b/archive", b), deleteAfterUpload = true)
        assertFalse(b.exists())
        assertEquals(1, first.objects.size)
        assertEquals(1, second.objects.size)
        val manifest = writer.finish()
        assertEquals(setOf("fast", "slow"), manifest.manifestCopies.keys)
        assertTrue(manifest.missingCopies.isEmpty())
        val bytes = first.objects.getValue(manifest.manifestCopies.getValue("fast"))
        assertTrue(bytes.contentEquals(second.objects.getValue(manifest.manifestCopies.getValue("slow"))))
        val progress = mutableListOf<Pair<Int, Int>>()
        val restored = CloudSnapshot(listOf(second, first), temporary.newFolder()).retrieve(bytes, passphrase) { done, total ->
            progress += done to total
        }
        assertEquals(listOf(0 to 2, 1 to 2, 2 to 2), progress)
        assertEquals("first", File(restored, "apps/a/archive").readText())
        assertEquals("second", File(restored, "apps/b/archive").readText())
    }

    @Test fun failedArchiveIsKeptAndIncompleteSnapshotCannotBePublished() = runBlocking {
        val drive = MemoryDrive("fast", IOException("offline"))
        val writer = CloudSnapshot(listOf(drive), temporary.newFolder()).begin(listOf(fast.copy(maxFileBytes = null)), "test-passphrase".toCharArray())
        val archive = temporary.newFile().apply { writeText("original") }
        val upload = runCatching { writer.append(CloudSnapshot.Source("apps/a/archive", archive), deleteAfterUpload = true) }
        assertTrue(upload.exceptionOrNull() is IOException)
        assertEquals("original", archive.readText())
        assertTrue(runCatching { writer.finish() }.isFailure)
        assertTrue(drive.objects.isEmpty())
    }

    @Test fun manifestRoundTripsAndRejectsTamperingOrDuplicatePaths() {
        val entry = CloudArchive("apps/app/archive", "account", "file", 42, "a".repeat(64))
        val passphrase = "test-passphrase".toCharArray()
        val encoded = CloudManifest.encode(listOf(entry), passphrase)
        assertEquals(listOf(entry), CloudManifest.decode(encoded, passphrase))
        assertThrows(IllegalArgumentException::class.java) {
            CloudManifest.decode(encoded, "wrong-passphrase".toCharArray())
        }
        encoded[encoded.lastIndex] = (encoded.last().toInt() xor 1).toByte()
        assertThrows(IllegalArgumentException::class.java) { CloudManifest.decode(encoded, passphrase) }
        assertThrows(IllegalArgumentException::class.java) { CloudManifest.encode(listOf(entry, entry), passphrase) }
    }

    @Test fun parsesSixSupportedDrivesAndSkipsOtherCredentials() {
        val platforms = mapOf(
            "quark" to "cookie", "uc" to "cookie", "baidu" to "cookie",
            "c139" to "cookie", "pan123" to "accessToken", "pan115" to "cookie",
        )
        val entries = platforms.map { (platform, key) -> """{"platform":"$platform","$key":"example-secret"}""" } +
            listOf(
                """{"platform":"video_bilibili","cookie":"ignored"}""",
                """{"platform":"lanzou","cookie":"ignored"}""",
                """{"platform":"xunlei","accessToken":"ignored"}""",
                """{"platform":"guangya","accessToken":"ignored"}""",
                """{"platform":"ilanzou","appToken":"ignored"}""",
                """{"platform":"quark","cookie":""}""",
            )
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
