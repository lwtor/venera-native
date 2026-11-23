package dev.veneranative.source.network

import dev.veneranative.core.model.SourceId
import dev.veneranative.source.api.SourceHostRequest
import dev.veneranative.source.api.SourceHostResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl

class SourceNetworkHostApiTest {

    @Test
    fun `cookie lookup only returns matching cookies from the requesting source`() = runTest {
        val registry = PerSourceCookieJarRegistry()
        val first = SourceId("source-a")
        val second = SourceId("source-b")
        val url = "https://example.com/".toHttpUrl()
        registry.forSource(first).saveFromResponse(
            url,
            listOf(Cookie.Builder().name("session").value("one").domain("example.com").build()),
        )
        registry.forSource(second).saveFromResponse(
            url,
            listOf(Cookie.Builder().name("session").value("two").domain("example.com").build()),
        )
        val api = SourceNetworkHostApi(SourceNetworkExecutor(cookieJars = registry))

        val result = api.invoke(
            SourceHostRequest(first, "call-1", "request-1", SourceNetworkHostApi.COOKIE_GET_METHOD, """{"url":"$url"}"""),
        ) as SourceHostResult.Success

        assertTrue(api.isMethodAllowed(SourceNetworkHostApi.COOKIE_GET_METHOD))
        assertEquals("""[{"name":"session","value":"one","domain":"example.com"}]""", result.resultJson)
    }

    @Test
    fun `timer sleep is allow listed and waits for the requested bounded duration`() = runTest {
        var requestedDelay = -1L
        val api = SourceNetworkHostApi(
            executor = SourceNetworkExecutor(),
            waitForTimer = { requestedDelay = it },
        )

        val result = api.invoke(timerRequest("timer.sleep", """{"timerId":"1","delayMillis":40000}"""))

        assertTrue(api.isMethodAllowed(SourceNetworkHostApi.TIMER_SLEEP_METHOD))
        assertEquals(40_000L, requestedDelay)
        assertTrue(result is SourceHostResult.Success)
    }

    @Test
    fun `clear timeout cancels its host wait`() = runTest {
        val waitStarted = CompletableDeferred<Unit>()
        val neverFinish = CompletableDeferred<Unit>()
        val api = SourceNetworkHostApi(
            executor = SourceNetworkExecutor(),
            waitForTimer = {
                waitStarted.complete(Unit)
                neverFinish.await()
            },
        )
        val timerScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sleeping = timerScope.async {
            runCatching {
                api.invoke(timerRequest("timer.sleep", """{"timerId":"cancel-me","delayMillis":40000}"""))
            }
        }
        waitStarted.await()

        val cancelled = api.invoke(timerRequest("timer.cancel", """{"timerId":"cancel-me"}"""))

        assertTrue(cancelled is SourceHostResult.Success)
        assertTrue(runCatching { sleeping.await() }.isFailure)
        timerScope.cancel()
    }

    @Test
    fun `timer delay over the sandbox limit is rejected`() = runTest {
        val api = SourceNetworkHostApi(SourceNetworkExecutor(), waitForTimer = {})

        val result = api.invoke(
            timerRequest(
                "timer.sleep",
                """{"timerId":"too-long","delayMillis":${SourceNetworkHostApi.MAX_TIMER_DELAY_MILLIS + 1}}""",
            ),
        )

        assertTrue(result is SourceHostResult.Failure)
    }

    private fun timerRequest(method: String, payload: String) = SourceHostRequest(
        sourceId = SourceId("test-source"),
        invocationId = "call-1",
        requestId = "request-1",
        method = method,
        payloadJson = payload,
    )
}
