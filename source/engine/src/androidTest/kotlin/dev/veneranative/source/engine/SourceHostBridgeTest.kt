package dev.veneranative.source.engine

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.veneranative.core.model.SourceId
import dev.veneranative.source.api.SourceCall
import dev.veneranative.source.api.SourceInstallResult
import dev.veneranative.source.api.SourcePackage
import dev.veneranative.source.api.SourceResult
import dev.veneranative.source.api.SourceRuntimeError
import dev.veneranative.source.network.SourceNetworkExecutor
import dev.veneranative.source.network.SourceNetworkHostApi
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assume.assumeFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SourceHostBridgeTest {
    private lateinit var context: Context
    private lateinit var fixtureScript: String
    private lateinit var server: MockWebServer
    private var runtime: AndroidJavaScriptRuntime? = null

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        fixtureScript =
            context.assets.open("source_fixture.js").bufferedReader().use { reader ->
                reader.readText()
            }
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = runBlocking {
        runtime?.close()
        server.close()
    }

    @Test
    fun fixtureCompletesGetAndPostThroughHostApi() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(404)
                .addHeader("X-Response", "fixture")
                .body("not-found")
                .build(),
        )
        server.enqueue(MockResponse.Builder().code(201).body("created").build())
        val sourceId = createRuntimeAndInstall("round-trip")

        val get =
            invokeSuccess(
                sourceId = sourceId,
                callId = "get",
                functionName = "hostGet",
                arguments = JSONArray().put(server.url("/get").toString()),
            )
        assertEquals(404, get.getInt("statusCode"))
        assertEquals("not-found", get.getString("body"))
        assertEquals("fixture", get.getJSONObject("headers").getJSONArray("X-Response").getString(0))
        val getRequest = server.takeRequest()
        assertEquals("GET", getRequest.method)
        assertEquals("engine", getRequest.headers["X-Fixture"])

        val post =
            invokeSuccess(
                sourceId = sourceId,
                callId = "post",
                functionName = "hostPost",
                arguments =
                    JSONArray()
                        .put(server.url("/post").toString())
                        .put("fixture-body"),
            )
        assertEquals(201, post.getInt("statusCode"))
        assertEquals("created", post.getString("body"))
        val postRequest = server.takeRequest()
        assertEquals("POST", postRequest.method)
        assertEquals("fixture-body", postRequest.body?.utf8())
    }

    @Test
    fun nonAllowListedHostMethodIsRejected() = runBlocking {
        val sourceId = createRuntimeAndInstall("forbidden")
        val result =
            runtime!!.invoke(
                SourceCall.InvokeFunction(
                    callId = "forbidden",
                    sourceId = sourceId,
                    functionName = "forbiddenHostCall",
                ),
            )

        assertTrue(result is SourceResult.Failure)
        assertTrue((result as SourceResult.Failure).error is SourceRuntimeError.ScriptExecution)
    }

    @Test
    fun runtimeCancellationCancelsUnderlyingOkHttpCall() = runBlocking {
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
        val sourceId =
            createRuntimeAndInstall(
                id = "cancel",
                executor = SourceNetworkExecutor(baseClient = client),
            )
        val pending =
            async {
                runtime!!.invoke(
                    SourceCall.InvokeFunction(
                        callId = "cancel-host",
                        sourceId = sourceId,
                        functionName = "hostGet",
                        argumentsJson = JSONArray().put(server.url("/slow").toString()).toString(),
                        timeoutMillis = 10_000,
                    ),
                )
            }

        assertTrue(requestArrived.await(2, TimeUnit.SECONDS))
        runtime!!.cancel("cancel-host")
        val result = pending.await()
        repeat(20) {
            if (!callCancelled.get()) Thread.sleep(10)
        }
        releaseResponse.countDown()
        assertTrue(result is SourceResult.Failure)
        assertTrue((result as SourceResult.Failure).error is SourceRuntimeError.Cancelled)
        assertTrue(callCancelled.get())
    }

    private suspend fun createRuntimeAndInstall(
        id: String,
        executor: SourceNetworkExecutor = SourceNetworkExecutor(),
    ): SourceId {
        runtime =
            AndroidJavaScriptRuntime(
                context = context,
                hostApi = SourceNetworkHostApi(executor),
            )
        val sourceId = SourceId(id)
        val result =
            runtime!!.install(
                SourcePackage(
                    sourceId = sourceId,
                    version = "1",
                    script = fixtureScript,
                    sha256 = sha256(fixtureScript),
                ),
            )
        assumeFalse(
            "Device JavaScript Sandbox does not support MessagePort Host APIs.",
            result is SourceInstallResult.Failed &&
                result.error is SourceRuntimeError.EngineUnavailable,
        )
        assertTrue("Expected source install, got $result", result is SourceInstallResult.Installed)
        return sourceId
    }

    private suspend fun invokeSuccess(
        sourceId: SourceId,
        callId: String,
        functionName: String,
        arguments: JSONArray,
    ): JSONObject {
        val result =
            runtime!!.invoke(
                SourceCall.InvokeFunction(
                    callId = callId,
                    sourceId = sourceId,
                    functionName = functionName,
                    argumentsJson = arguments.toString(),
                ),
            )
        assertTrue("Expected success, got $result", result is SourceResult.Success)
        return JSONObject((result as SourceResult.Success).json)
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
}
