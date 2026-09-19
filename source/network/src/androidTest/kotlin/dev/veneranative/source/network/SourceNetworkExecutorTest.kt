package dev.veneranative.source.network

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.veneranative.core.model.SourceId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SourceNetworkExecutorTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun getAndPostPreserveStructuredResponses() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(404)
                .addHeader("X-Fixture", "get")
                .body("missing")
                .build(),
        )
        server.enqueue(MockResponse.Builder().code(201).body("created").build())
        val executor = SourceNetworkExecutor()
        val sourceId = SourceId("requests")

        val get =
            executor.execute(
                sourceId,
                SourceHttpRequest(
                    url = server.url("/get").toString(),
                    method = SourceHttpRequest.Method.GET,
                    headers = mapOf("X-Test" to "yes"),
                ),
            ) as SourceHttpResult.Success
        assertEquals(404, get.response.statusCode)
        assertEquals("missing", get.response.body)
        assertEquals("get", get.response.headers["X-Fixture"]?.single())
        val getRequest = server.takeRequest()
        assertEquals("GET", getRequest.method)
        assertEquals("yes", getRequest.headers["X-Test"])

        val post =
            executor.execute(
                sourceId,
                SourceHttpRequest(
                    url = server.url("/post").toString(),
                    method = SourceHttpRequest.Method.POST,
                    headers = mapOf("Content-Type" to "application/json"),
                    body = """{"name":"fixture"}""",
                ),
            ) as SourceHttpResult.Success
        assertEquals(201, post.response.statusCode)
        val postRequest = server.takeRequest()
        assertEquals("POST", postRequest.method)
        assertEquals("""{"name":"fixture"}""", postRequest.body?.utf8())
    }

    @Test
    fun cookiesAreIsolatedBySource() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .addHeader("Set-Cookie", "session=source-a; Path=/")
                .build(),
        )
        server.enqueue(MockResponse.Builder().build())
        server.enqueue(MockResponse.Builder().build())
        val executor = SourceNetworkExecutor()
        val sourceA = SourceId("cookie-a")
        val sourceB = SourceId("cookie-b")
        val request =
            SourceHttpRequest(
                url = server.url("/cookie").toString(),
                method = SourceHttpRequest.Method.GET,
            )

        executor.execute(sourceA, request)
        executor.execute(sourceB, request)
        executor.execute(sourceA, request)

        assertNull(server.takeRequest().headers["Cookie"])
        assertNull(server.takeRequest().headers["Cookie"])
        assertEquals("session=source-a", server.takeRequest().headers["Cookie"])
    }

    @Test
    fun connectionFailureUsesStableError() = runBlocking {
        val url = server.url("/closed").toString()
        server.close()
        val result =
            SourceNetworkExecutor().execute(
                SourceId("connection"),
                SourceHttpRequest(url = url, method = SourceHttpRequest.Method.GET),
            )

        assertTrue(
            result is SourceHttpResult.Failure &&
                result.error is SourceNetworkError.Connection,
        )
        server = MockWebServer().apply { start() }
    }

    @Test
    fun concurrentOverflowFailsWithoutQueueing() = runBlocking {
        val requestArrived = CountDownLatch(1)
        val releaseResponse = CountDownLatch(1)
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requestArrived.countDown()
                    releaseResponse.await(5, TimeUnit.SECONDS)
                    return MockResponse.Builder().body("done").build()
                }
            }
        val executor = SourceNetworkExecutor(maxConcurrentPerSource = 1)
        val sourceId = SourceId("limited")
        val request =
            SourceHttpRequest(
                url = server.url("/slow").toString(),
                method = SourceHttpRequest.Method.GET,
            )

        val first = async { executor.execute(sourceId, request) }
        assertTrue(requestArrived.await(2, TimeUnit.SECONDS))
        val overflow = executor.execute(sourceId, request)
        assertTrue(
            overflow is SourceHttpResult.Failure &&
                overflow.error is SourceNetworkError.ConcurrencyLimit,
        )
        releaseResponse.countDown()
        assertTrue(first.await() is SourceHttpResult.Success)
    }

    @Test
    fun coroutineCancellationCancelsOkHttpCall() = runBlocking {
        val requestArrived = CountDownLatch(1)
        val releaseResponse = CountDownLatch(1)
        val callCancelled = AtomicBoolean(false)
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requestArrived.countDown()
                    releaseResponse.await(5, TimeUnit.SECONDS)
                    return MockResponse.Builder().body("late").build()
                }
            }
        val client =
            OkHttpClient.Builder()
                .eventListener(
                    object : EventListener() {
                        override fun canceled(call: Call) {
                            callCancelled.set(true)
                        }
                    },
                )
                .build()
        val executor = SourceNetworkExecutor(baseClient = client)
        val pending =
            async {
                executor.execute(
                    SourceId("cancelled"),
                    SourceHttpRequest(
                        url = server.url("/cancel").toString(),
                        method = SourceHttpRequest.Method.GET,
                    ),
                )
            }

        assertTrue(requestArrived.await(2, TimeUnit.SECONDS))
        pending.cancelAndJoin()
        repeat(20) {
            if (callCancelled.get()) return@repeat
            Thread.sleep(10)
        }
        releaseResponse.countDown()
        assertTrue(callCancelled.get())
        assertTrue(pending.isCancelled)
    }
}
