package dev.veneranative.source.network

import dev.veneranative.core.model.SourceId
import java.io.IOException
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
        assertEquals(SourceNetworkError.Connection, (result as SourceHttpResult.Failure).error)
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
}
