package dev.veneranative.data.backup

import kotlinx.coroutines.runBlocking
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test

class GitHubDeviceFlowTest {
    @Test fun `device authorization requests an expiring token with refresh support`() = runBlocking {
        var scope: String? = null
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val body = chain.request().body as FormBody
            scope = (0 until body.size).firstOrNull { body.name(it) == "scope" }?.let(body::value)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK")
                .body("""{"device_code":"device","user_code":"CODE","verification_uri":"https://github.com/login/device","expires_in":900,"interval":5}""".toResponseBody())
                .build()
        }.build()

        GitHubAppApi(client, "public-client-id") { 0 }.beginDeviceAuthorization()

        assertEquals("offline_access", scope)
    }
}
