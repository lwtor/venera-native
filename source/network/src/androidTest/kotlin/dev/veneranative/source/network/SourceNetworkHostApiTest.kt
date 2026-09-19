package dev.veneranative.source.network

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.veneranative.core.model.SourceId
import dev.veneranative.source.api.SourceHostError
import dev.veneranative.source.api.SourceHostRequest
import dev.veneranative.source.api.SourceHostResult
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SourceNetworkHostApiTest {
    @Test
    fun hostResponseIsJsonAndUnknownMethodsAreDenied() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(202).body("accepted").build())
            val host = SourceNetworkHostApi(SourceNetworkExecutor())
            val request =
                SourceHostRequest(
                    sourceId = SourceId("host"),
                    invocationId = "invoke",
                    requestId = "request",
                    method = SourceNetworkHostApi.HTTP_REQUEST_METHOD,
                    payloadJson =
                        """{"url":"${server.url("/")}","method":"GET","headers":{}}""",
                )

            val result = host.invoke(request)
            assertTrue(result is SourceHostResult.Success)
            assertTrue((result as SourceHostResult.Success).resultJson.contains("\"statusCode\":202"))

            val denied = host.invoke(request.copy(requestId = "denied", method = "files.read"))
            assertTrue(denied is SourceHostResult.Failure)
            assertEquals(
                SourceHostError.Code.METHOD_NOT_ALLOWED,
                (denied as SourceHostResult.Failure).error.code,
            )
        }
    }

    @Test
    fun invalidPayloadAndRedactionNeverExposeToken() = runBlocking {
        val token = "secret-token-value"
        val host = SourceNetworkHostApi(SourceNetworkExecutor())
        val result =
            host.invoke(
                SourceHostRequest(
                    sourceId = SourceId("redaction"),
                    invocationId = "invoke",
                    requestId = "request",
                    method = SourceNetworkHostApi.HTTP_REQUEST_METHOD,
                    payloadJson = """{"Authorization":"Bearer $token","url":null}""",
                ),
            )

        assertTrue(result is SourceHostResult.Failure)
        val error = (result as SourceHostResult.Failure).error
        assertFalse(error.message.contains(token))
        val redacted =
            SensitiveDataRedactor.redactHeaders(
                mapOf("Authorization" to "Bearer $token", "X-Trace" to "safe"),
            )
        assertEquals(SensitiveDataRedactor.REDACTED, redacted["Authorization"])
        assertEquals("safe", redacted["X-Trace"])
        assertFalse(redacted.toString().contains(token))
    }
}
