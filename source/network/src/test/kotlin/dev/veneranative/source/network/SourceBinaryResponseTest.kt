package dev.veneranative.source.network

import dev.veneranative.core.model.SourceId
import dev.veneranative.source.api.SourceHostRequest
import dev.veneranative.source.api.SourceHostResult
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class SourceBinaryResponseTest {
    @Test fun `binary responses preserve null invalid utf8 and chinese encoded bytes`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val bytes = byteArrayOf(0, 0xff.toByte(), 0x80.toByte()) + "中文".toByteArray()
            server.enqueue(MockResponse.Builder().body(Buffer().write(bytes)).build())
            val host = SourceNetworkHostApi(SourceNetworkExecutor())
            val result = host.invoke(request("""{"url":"${server.url("/bytes")}","method":"GET","responseType":"bytes"}"""))
            assertTrue(result is SourceHostResult.Success)
            val payload = Json.parseToJsonElement((result as SourceHostResult.Success).resultJson).jsonObject
            assertEquals("200", payload.getValue("statusCode").jsonPrimitive.content)
            assertEquals("", payload.getValue("body").jsonPrimitive.content)
            assertArrayEquals(bytes, Base64.getDecoder().decode(payload.getValue("bodyBase64").jsonPrimitive.content))
        }
    }

    @Test fun `empty binary body is distinguishable from text response`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().body("").build())
            server.enqueue(MockResponse.Builder().body("中文").build())
            val executor = SourceNetworkExecutor()
            val bytes = executor.execute(SourceId("bytes"), SourceHttpRequest(server.url("/empty").toString(), SourceHttpRequest.Method.GET, responseAsBytes = true)) as SourceHttpResult.Success
            assertEquals("", bytes.response.bodyBase64)
            val text = executor.execute(SourceId("bytes"), SourceHttpRequest(server.url("/text").toString(), SourceHttpRequest.Method.GET)) as SourceHttpResult.Success
            assertEquals("中文", text.response.body)
            assertNull(text.response.bodyBase64)
        }
    }

    @Test fun `binary requests retain streaming decoded size bound`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().chunkedBody(Buffer().write(ByteArray(33)), 8).build())
            val executor = SourceNetworkExecutor(maxResponseBytes = 32)
            val result = executor.execute(SourceId("bytes"), SourceHttpRequest(server.url("/large").toString(), SourceHttpRequest.Method.GET, responseAsBytes = true))
            assertEquals(SourceHttpResult.Failure(SourceNetworkError.ResponseTooLarge(32)), result)
        }
    }

    @Test fun `invalid response type is rejected before network request`() = runBlocking {
        val host = SourceNetworkHostApi(SourceNetworkExecutor())
        for (type in listOf("true", "null", "42", "{}", "\"arbitrary\"")) {
            val result = host.invoke(request("""{"url":"https://example.invalid/","method":"GET","responseType":$type}"""))
            assertTrue(result is SourceHostResult.Failure)
            assertEquals(dev.veneranative.source.api.SourceHostError.Code.INVALID_REQUEST, (result as SourceHostResult.Failure).error.code)
        }
    }

    private fun request(payload: String) = SourceHostRequest(SourceId("bytes"), "call", "req", "http.request", payload)
}
