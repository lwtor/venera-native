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

class SourceNetworkHostApiTest {

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
