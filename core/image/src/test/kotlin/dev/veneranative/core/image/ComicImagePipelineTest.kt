package dev.veneranative.core.image

import coil3.disk.DiskCache
import dev.veneranative.core.model.SourceId
import kotlinx.coroutines.*
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.TimeUnit

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

    @Test fun slowImageBodyIsBoundedByWholeCallTimeout() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val cache = DiskCache.Builder().directory(temp.newFolder().toOkioPath()).maxSizeBytes(1024 * 1024).build()
            try {
                val pipeline = CoilComicImagePipeline(
                    OkHttpClient(), cache, ComicImageAuthProvider { _, _ -> emptyMap() },
                    callTimeoutMillis = 150,
                )
                server.enqueue(
                    MockResponse.Builder()
                        .body("0123456789")
                        .throttleBody(1, 100, TimeUnit.MILLISECONDS)
                        .build(),
                )

                val result = withTimeout(3_000) {
                    pipeline.cachedFileOf(ComicImageRequest(server.url("/slow").toString()))
                }

                assertNull(result)
            } finally { cache.shutdown() }
        }
    }

    @Test fun browserUserAgentIsUsedByDefaultAndSourceOverrideWins() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val cache = DiskCache.Builder().directory(temp.newFolder().toOkioPath()).maxSizeBytes(1024 * 1024).build()
            try {
                val pipeline = CoilComicImagePipeline(OkHttpClient(), cache, ComicImageAuthProvider { _, _ -> emptyMap() })
                server.enqueue(MockResponse.Builder().body("first").build())
                server.enqueue(MockResponse.Builder().body("second").build())

                pipeline.cachedFileOf(ComicImageRequest(server.url("/default").toString()))?.close()
                pipeline.cachedFileOf(
                    ComicImageRequest(
                        server.url("/override").toString(),
                        headers = mapOf("User-Agent" to "source-image-agent"),
                    ),
                )?.close()

                assertTrue(server.takeRequest().headers["User-Agent"].orEmpty().contains("Chrome/"))
                assertEquals("source-image-agent", server.takeRequest().headers["User-Agent"])
            } finally { cache.shutdown() }
        }
    }

    @Test fun copyMangaRetriesForbiddenImageWithItsSourceUserAgent() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val cache = DiskCache.Builder().directory(temp.newFolder().toOkioPath()).maxSizeBytes(1024 * 1024).build()
            try {
                val pipeline = CoilComicImagePipeline(OkHttpClient(), cache, ComicImageAuthProvider { _, _ -> emptyMap() })
                server.enqueue(MockResponse.Builder().code(403).body("denied").build())
                server.enqueue(MockResponse.Builder().body("page bytes").build())
                val request = ComicImageRequest(server.url("/page.webp").toString(), SourceId("copy_manga"))

                pipeline.cachedFileOf(request)!!.use { assertEquals("page bytes", it.file.readText()) }

                assertTrue(server.takeRequest().headers["User-Agent"].orEmpty().contains("Chrome/"))
                assertEquals("COPY/3.0.6", server.takeRequest().headers["User-Agent"])
                pipeline.cachedFileOf(request)!!.use { assertEquals("page bytes", it.file.readText()) }
                assertEquals("403 response is not cached; successful fallback is cached", 2, server.requestCount)
            } finally { cache.shutdown() }
        }
    }

    @Test fun explicitCopyMangaImageUserAgentDoesNotGetOverriddenAfterDenial() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val cache = DiskCache.Builder().directory(temp.newFolder().toOkioPath()).maxSizeBytes(1024 * 1024).build()
            try {
                val pipeline = CoilComicImagePipeline(OkHttpClient(), cache, ComicImageAuthProvider { _, _ -> emptyMap() })
                server.enqueue(MockResponse.Builder().code(403).body("denied").build())

                assertNull(
                    pipeline.cachedFileOf(
                        ComicImageRequest(
                            server.url("/page.webp").toString(),
                            SourceId("copy_manga"),
                            headers = mapOf("User-Agent" to "source-selected-agent"),
                        ),
                    ),
                )

                assertEquals("source-selected-agent", server.takeRequest().headers["User-Agent"])
                assertEquals(1, server.requestCount)
            } finally { cache.shutdown() }
        }
    }
}
