package dev.veneranative.data.update

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ApkDownloaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val downloader = ApkDownloader(OkHttpClient(), progressIntervalBytes = 1024)

    @Test
    fun `downloads an apk with progress and leaves no part file`() = runTest {
        MockWebServer().use { server ->
            server.start()
            val bytes = apkBytes(4096)
            server.enqueue(MockResponse.Builder().body(Buffer().write(bytes)).build())
            val target = File(tmp.newFolder(), "update.apk")

            val progress = mutableListOf<ApkDownloadProgress>()
            val result = downloader.download(server.url("/release.apk").toString(), target, progress::add)

            assertEquals(target, result)
            assertTrue(target.isFile)
            assertEquals(bytes.size.toLong(), target.length())
            assertFalse(File(target.parentFile, target.name + ".part").exists())
            assertEquals(bytes.size.toLong(), progress.last().bytesDownloaded)
            assertEquals(bytes.size.toLong(), progress.last().totalBytes)
            // A small body arrives in one buffered read, so every report already carries the
            // full byte count; the guarantees that matter are monotonicity and completeness.
            assertTrue(progress.zipWithNext().all { (earlier, later) -> later.bytesDownloaded >= earlier.bytesDownloaded })
            assertEquals(1f, progress.last().fraction!!, 0.0001f)
        }
    }

    @Test
    fun `rejects a body that is not an apk`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().body("<html>404 page</html>").build())
            val target = File(tmp.newFolder(), "update.apk")

            val failure = runCatching {
                downloader.download(server.url("/release.apk").toString(), target)
            }.exceptionOrNull()

            assertTrue(failure is ApkDownloadException)
            assertTrue(failure!!.message!!.contains("not an Android package"))
            assertFalse(target.exists())
            assertFalse(File(target.parentFile, target.name + ".part").exists())
        }
    }

    @Test
    fun `maps an http error to a download exception and writes nothing`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(404).body("gone").build())
            val target = File(tmp.newFolder(), "update.apk")

            val failure = runCatching {
                downloader.download(server.url("/release.apk").toString(), target)
            }.exceptionOrNull()

            assertTrue(failure is ApkDownloadException)
            assertTrue(failure!!.message!!.contains("404"))
            assertFalse(target.exists())
            assertFalse(File(target.parentFile, target.name + ".part").exists())
        }
    }

    @Test
    fun `replaces a stale file from an earlier download attempt`() = runTest {
        MockWebServer().use { server ->
            server.start()
            val bytes = apkBytes(2048)
            server.enqueue(MockResponse.Builder().body(Buffer().write(bytes)).build())
            val folder = tmp.newFolder()
            val target = File(folder, "update.apk").apply { writeBytes(ByteArray(16)) }

            downloader.download(server.url("/release.apk").toString(), target)

            assertEquals(bytes.size.toLong(), target.length())
            assertEquals(1, Files.list(folder.toPath()).use { it.count() })
        }
    }

    @Test
    fun `resumes a partial download via a range request`() = runTest {
        MockWebServer().use { server ->
            server.start()
            val bytes = apkBytes(4096)
            // The client asks for bytes 2048- with a range request.
            server.enqueue(
                MockResponse.Builder()
                    .code(206)
                    .addHeader("Content-Range", "bytes 2048-4095/4096")
                    .body(Buffer().write(bytes.copyOfRange(2048, 4096)))
                    .build(),
            )
            val folder = tmp.newFolder()
            val target = File(folder, "update.apk")
            // A prior attempt left the first half behind.
            File(folder, "update.apk.part").writeBytes(bytes.copyOfRange(0, 2048))

            val result = downloader.download(server.url("/release.apk").toString(), target)

            assertEquals(target, result)
            assertEquals(4096L, target.length())
            assertTrue(target.readBytes().contentEquals(bytes))
            val request = server.takeRequest()
            assertTrue(request.headers["Range"]!!.startsWith("bytes=2048-"))
        }
    }

    @Test
    fun `keeps the part file when the download fails mid-transfer`() = runTest {
        MockWebServer().use { server ->
            server.start()
            val bytes = apkBytes(4096)
            val folder = tmp.newFolder()
            val target = File(folder, "update.apk")
            val part = File(folder, "update.apk.part")
            // A prior attempt left the first half behind.
            part.writeBytes(bytes.copyOfRange(0, 2048))

            // This attempt fails before completing (an HTTP error). The `.part` from the previous
            // run must survive so the next attempt can resume instead of restarting. The bug this
            // guards against is a `finally { part.delete() }` that wiped the resume point on every
            // failure, forcing a restart from zero.
            server.enqueue(MockResponse.Builder().code(500).body("boom").build())
            val failure = runCatching {
                downloader.download(server.url("/release.apk").toString(), target)
            }.exceptionOrNull()
            assertTrue(failure is ApkDownloadException)
            assertTrue("the .part must survive a failed attempt", part.isFile)
            assertEquals(2048L, part.length())
            assertFalse(target.exists())
        }
    }

    private fun apkBytes(size: Int): ByteArray = ByteArray(size).also { bytes ->
        // APK files are ZIP archives starting with a local file header.
        bytes[0] = 0x50
        bytes[1] = 0x4B
        bytes[2] = 0x03
        bytes[3] = 0x04
    }
}
