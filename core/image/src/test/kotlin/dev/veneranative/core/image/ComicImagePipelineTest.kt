package dev.veneranative.core.image

import coil3.disk.DiskCache
import kotlinx.coroutines.*
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ComicImagePipelineTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun firstDownloadAndCacheHitOwnReadableFiles() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val cache = DiskCache.Builder().directory(temp.newFolder().toOkioPath()).maxSizeBytes(1024 * 1024).build()
            try {
                val pipeline = CoilComicImagePipeline(OkHttpClient(), cache, ComicImageAuthProvider { _, _ -> emptyMap() })
                server.enqueue(MockResponse.Builder().body("pixels").build())
                val request = ComicImageRequest(server.url("/").toString())
                pipeline.cachedFileOf(request)!!.use { assertEquals("pixels", it.file.readText()) }
                pipeline.cachedFileOf(request)!!.use { assertEquals("pixels", it.file.readText()) }
                assertEquals(1, server.requestCount)
            } finally { cache.shutdown() }
        }
    }
    @Test fun unknownLengthLimitAndEncodedPostHeaders() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val cache = DiskCache.Builder().directory(temp.newFolder().toOkioPath()).maxSizeBytes(1024 * 1024).build()
            try {
                var authReads = 0
                val pipeline = CoilComicImagePipeline(OkHttpClient(), cache, ComicImageAuthProvider { _, _ -> authReads++; mapOf("Cookie" to "session=$authReads") }, maxBytes = 8)
                server.enqueue(MockResponse.Builder().chunkedBody("123456789", 2).build())
                val request = ComicImageRequest(server.url("/").toString(), method = ComicImageMethod.POST,
                    body = ComicImageBody.Form(mapOf("a&b" to "x=y+z")), referer = "https://example.invalid/")
                assertNull(pipeline.cachedFileOf(request))
                val recorded = server.takeRequest()
                assertEquals("a%26b=x%3Dy%2Bz", recorded.body!!.utf8())
                assertEquals("session=1", recorded.headers["Cookie"])
                assertEquals("https://example.invalid/", recorded.headers["Referer"])
                assertEquals(1, authReads)
            } finally { cache.shutdown() }
        }
    }
}
