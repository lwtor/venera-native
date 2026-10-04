package dev.veneranative.source.network

import dev.veneranative.core.model.SourceId
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.*
import okio.*
import org.junit.Assert.*
import org.junit.Test

class SourceNetworkBodyTest {
    @Test fun komiicDebugTraceIdentifiesNetworkStagesWithoutLoggingRequestSecrets() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().body("ok").build())
            val events = mutableListOf<String>()
            val result = SourceNetworkExecutor(
                diagnosticSourceIds = setOf(SourceId("Komiic")),
                onNetworkTrace = { _, event -> synchronized(events) { events += event } },
            ).execute(
                SourceId("Komiic"),
                SourceHttpRequest(
                    url = server.url("/api/query?keyword=private-search-term").toString(),
                    method = SourceHttpRequest.Method.POST,
                    headers = mapOf("Cookie" to "session=private-cookie"),
                    body = "private-request-body",
                ),
            )

            assertTrue(result is SourceHttpResult.Success)
            val trace = synchronized(events) { events.joinToString("\n") }
            assertTrue(trace.contains("stage=dnsStart"))
            assertTrue(trace.contains("stage=dnsEnd"))
            assertTrue(trace.contains("stage=connectStart"))
            assertTrue(trace.contains("stage=requestBodyEnd"))
            assertTrue(trace.contains("stage=responseHeadersEnd status=200"))
            assertTrue(trace.contains("stage=callEnd"))
            assertFalse(trace.contains("private-search-term"))
            assertFalse(trace.contains("private-cookie"))
            assertFalse(trace.contains("private-request-body"))
            assertFalse(trace.contains("/api/query"))
        }
    }

    @Test fun komiicGraphQlPostIsSentWithJsonHeadersAndBody() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().body("""{"data":{"ok":true}}""").build())
            val body = """{"operationName":"searchComicAndAuthorQuery","variables":{"keyword":"舞舞舞"}}"""
            val result = SourceNetworkExecutor().execute(
                SourceId("Komiic"),
                SourceHttpRequest(
                    url = server.url("/api/query").toString(),
                    method = SourceHttpRequest.Method.POST,
                    headers = mapOf(
                        "Content-Type" to "application/json",
                        "Referer" to "https://komiic.com/",
                    ),
                    body = body,
                ),
            ) as SourceHttpResult.Success

            assertEquals("{\"data\":{\"ok\":true}}", result.response.body)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/query", request.url.encodedPath)
            assertEquals("application/json", request.headers["Content-Type"])
            assertEquals("https://komiic.com/", request.headers["Referer"])
            assertEquals(body, request.body?.utf8())
        }
    }

    @Test fun sourceRequestsHaveABoundedTotalTimeout() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse.Builder()
                    .body("late response")
                    .bodyDelay(500, TimeUnit.MILLISECONDS)
                    .build(),
            )

            val result = SourceNetworkExecutor(callTimeoutMillis = 100).execute(
                SourceId("slow-source"),
                SourceHttpRequest(server.url("/slow").toString(), SourceHttpRequest.Method.GET),
            )

            assertEquals(SourceNetworkError.Timeout, (result as SourceHttpResult.Failure).error)
        }
    }

    @Test fun unsupportedSourceCompressionHeaderDoesNotBreakJsonResponses() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val expected = "{\"items\":[1,2]}"
            val compressed = ByteArrayOutputStream().use { bytes ->
                GZIPOutputStream(bytes).use { gzip -> gzip.write(expected.toByteArray(Charsets.UTF_8)) }
                bytes.toByteArray()
            }
            server.enqueue(
                MockResponse.Builder()
                    .addHeader("Content-Encoding", "gzip")
                    .body(Buffer().write(compressed))
                    .build(),
            )

            val result = SourceNetworkExecutor().execute(
                SourceId("compressed-source"),
                SourceHttpRequest(
                    url = server.url("/search").toString(),
                    method = SourceHttpRequest.Method.GET,
                    headers = mapOf("Accept-Encoding" to "gzip, deflate, br, zstd"),
                ),
            ) as SourceHttpResult.Success

            assertEquals(expected, result.response.body)
            assertEquals("gzip", server.takeRequest().headers["Accept-Encoding"])
        }
    }

    @Test fun shortEmptyAndExactBodiesComplete() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val executor = SourceNetworkExecutor(maxResponseBytes = 8)
            for (body in listOf("", "missing", "12345678")) {
                server.enqueue(MockResponse.Builder().body(body).build())
                val result = withTimeout(2000) { executor.execute(SourceId("test"), SourceHttpRequest(server.url("/").toString(), SourceHttpRequest.Method.GET)) }
                assertEquals(body, (result as SourceHttpResult.Success).response.body)
            }
        }
    }
    @Test fun unknownLengthOverflowIsBounded() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().chunkedBody("123456789", 2).build())
            val result = withTimeout(2000) { SourceNetworkExecutor(maxResponseBytes = 8).execute(SourceId("test"), SourceHttpRequest(server.url("/").toString(), SourceHttpRequest.Method.GET)) }
            assertTrue((result as SourceHttpResult.Failure).error is SourceNetworkError.ResponseTooLarge)
        }
    }
    @Test fun bodyReadFailureCompletesWithDomainError() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(object : ResponseBody() {
                    override fun contentType(): MediaType? = null
                    override fun contentLength() = -1L
                    override fun source(): BufferedSource = object : Source {
                        override fun read(sink: Buffer, byteCount: Long): Long = throw IOException("test read failure")
                        override fun timeout() = Timeout.NONE
                        override fun close() = Unit
                    }.buffer()
                }).build()
        }.build()
        val result = withTimeout(2000) { SourceNetworkExecutor(baseClient = client).execute(SourceId("test"), SourceHttpRequest("https://example.invalid/", SourceHttpRequest.Method.GET)) }
        assertTrue((result as SourceHttpResult.Failure).error is SourceNetworkError.Connection)
    }
    @Test fun clearingASourceCancelsItsActiveRequest() = runBlocking {
        val started = CountDownLatch(1)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            started.countDown()
            while (!chain.call().isCanceled()) Thread.sleep(10)
            throw IOException("source cleared")
        }.build()
        val source = SourceId("cleared")
        val executor = SourceNetworkExecutor(baseClient = client)
        val request = async(Dispatchers.IO) {
            executor.execute(source, SourceHttpRequest("https://example.invalid/", SourceHttpRequest.Method.GET))
        }
        assertTrue(started.await(2, TimeUnit.SECONDS))

        executor.clearSource(source)

        val result = withTimeout(2_000) { request.await() }
        assertEquals(SourceNetworkError.Cancelled, (result as SourceHttpResult.Failure).error)
    }

    @Test fun requestsBeyondThePerSourceLimitWaitForAnAvailablePermit() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().body("first").build())
            server.enqueue(MockResponse.Builder().body("second").build())
            val firstStarted = CountDownLatch(1)
            val releaseFirst = CountDownLatch(1)
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                if (chain.request().url.encodedPath == "/first") {
                    firstStarted.countDown()
                    check(releaseFirst.await(2, TimeUnit.SECONDS))
                }
                chain.proceed(chain.request())
            }.build()
            val executor = SourceNetworkExecutor(baseClient = client, maxConcurrentPerSource = 1)
            val source = SourceId("serial-source")
            val first = async(Dispatchers.IO) {
                executor.execute(source, SourceHttpRequest(server.url("/first").toString(), SourceHttpRequest.Method.GET))
            }
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS))
            val second = async(Dispatchers.IO) {
                executor.execute(source, SourceHttpRequest(server.url("/second").toString(), SourceHttpRequest.Method.GET))
            }
            yield()
            assertFalse("the next request must queue instead of fail", second.isCompleted)
            releaseFirst.countDown()

            assertEquals("first", ((first.await() as SourceHttpResult.Success).response.body))
            assertEquals("second", ((second.await() as SourceHttpResult.Success).response.body))
        }
    }
}
