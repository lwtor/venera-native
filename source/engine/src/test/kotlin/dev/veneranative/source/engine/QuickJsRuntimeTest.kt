package dev.veneranative.source.engine

import dev.veneranative.core.model.SourceId
import dev.veneranative.source.api.SourceCall
import dev.veneranative.source.api.SourceHostApi
import dev.veneranative.source.api.SourceHostRequest
import dev.veneranative.source.api.SourceHostResult
import dev.veneranative.source.api.SourceInstallResult
import dev.veneranative.source.api.SourcePackage
import dev.veneranative.source.api.SourceResult
import dev.veneranative.source.api.SourceRuntimeError
import dev.veneranative.source.api.SourceScriptRuntime
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The owned runtime, exercised on the JVM (no device).
 *
 * `runBlocking` rather than `runTest` on purpose: timeout and cancellation must be measured against
 * real engine work, and the test scheduler's virtual clock would fire timeouts before the engine has
 * done anything.
 */
class QuickJsRuntimeTest {

    @Test
    fun `an installed source exposes its globals`() = runBlocking {
        withRuntime(QuickJsRuntime()) { runtime ->
            val sourceId = runtime.installSource(ECHO_SCRIPT)
            val result = runtime.invokeSuccess(sourceId, "add", "[2,3]")

            assertEquals(5, JSONObject(result).getInt("sum"))
        }
    }

    @Test
    fun `a source awaits fetch and reads a json body`() = runBlocking {
        val recordedUrls = CopyOnWriteArrayList<String>()
        val host = RecordingHostApi { request ->
            val payload = JSONObject(request.payloadJson)
            recordedUrls += payload.getString("url")
            SourceHostResult.Success(
                requestId = request.requestId,
                resultJson =
                    JSONObject()
                        .put("statusCode", 200)
                        .put(
                            "headers",
                            JSONObject().put("Content-Type", JSONArray().put("application/json")),
                        )
                        .put("body", """{"items":[7,8]}""")
                        .toString(),
            )
        }

        withRuntime(QuickJsRuntime(hostApi = host)) { runtime ->
            val sourceId = runtime.installSource(FETCH_SCRIPT)
            val result = runtime.invokeSuccess(sourceId, "search", """["cats and dogs"]""")
            val decoded = JSONObject(result)

            assertTrue("expected ok, got $result", decoded.getBoolean("ok"))
            assertEquals(200, decoded.getInt("status"))
            assertEquals(2, decoded.getInt("count"))
            assertEquals(1, recordedUrls.size)
            assertTrue(
                "keyword should reach the host: ${recordedUrls.single()}",
                recordedUrls.single().contains("cats%20and%20dogs"),
            )
        }
    }

    @Test
    fun `a binary request body survives as its own text`() = runBlocking {
        val sentBodies = CopyOnWriteArrayList<String>()
        val host = RecordingHostApi { request ->
            val payload = JSONObject(request.payloadJson)
            sentBodies += payload.get("body").toString()
            SourceHostResult.Success(
                requestId = request.requestId,
                resultJson =
                    JSONObject()
                        .put("statusCode", 201)
                        .put("headers", JSONObject())
                        .put("body", "created")
                        .toString(),
            )
        }

        withRuntime(QuickJsRuntime(hostApi = host)) { runtime ->
            val sourceId = runtime.installSource(FORM_SCRIPT)
            val result = runtime.invokeSuccess(sourceId, "submit", """["标题=中文 ✓"]""")

            assertEquals(201, JSONObject(result).getInt("status"))
            // Convert.encodeUtf8 produces bytes; the transport carries text, so the body must come
            // back as exactly the string that was encoded.
            assertEquals(listOf("标题=中文 ✓"), sentBodies)
        }
    }

    @Test
    fun `console output reaches the host log`() = runBlocking {
        val logs = CopyOnWriteArrayList<Pair<String, String>>()
        val runtime = QuickJsRuntime(logSink = { level, message -> logs += level to message })

        withRuntime(runtime) { active ->
            val sourceId = active.installSource(
                """
                function shout() {
                  console.warn("careful", { depth: 2 });
                  return "done";
                }
                """.trimIndent(),
            )
            active.invokeSuccess(sourceId, "shout", "[]")
        }

        assertEquals(1, logs.size)
        assertEquals("warn", logs.single().first)
        assertEquals("""careful {"depth":2}""", logs.single().second)
    }

    @Test
    fun `the app locale is exposed to sources`() = runBlocking {
        withRuntime(QuickJsRuntime(appLocale = "zh_CN")) { runtime ->
            val sourceId = runtime.installSource("""function locale() { return APP.locale; }""")

            assertEquals("\"zh_CN\"", runtime.invokeSuccess(sourceId, "locale", "[]"))
        }
    }

    @Test
    fun `a source failure becomes a script execution error`() = runBlocking {
        withRuntime(QuickJsRuntime()) { runtime ->
            val sourceId = runtime.installSource("""function fail() { throw new Error("boom"); }""")
            val result = runtime.invoke(SourceCall.InvokeFunction("fail-1", sourceId, "fail"))

            val error = (result as SourceResult.Failure).error
            assertTrue("expected ScriptExecution, got $error", error is SourceRuntimeError.ScriptExecution)
            assertTrue(error.message.contains("boom"))
        }
    }

    @Test
    fun `a host method outside the allow list is rejected`() = runBlocking {
        val host = RecordingHostApi { request ->
            SourceHostResult.Success(request.requestId, "{}")
        }

        withRuntime(QuickJsRuntime(hostApi = host)) { runtime ->
            val sourceId = runtime.installSource(
                """async function read() { return veneraHost.call("files.read", { path: "/etc" }); }""",
            )
            val result = runtime.invoke(SourceCall.InvokeFunction("read-1", sourceId, "read"))

            val error = (result as SourceResult.Failure).error
            assertTrue("expected ScriptExecution, got $error", error is SourceRuntimeError.ScriptExecution)
            assertTrue(error.message.contains("not allowed"))
            assertEquals(0, host.requests.size)
        }
    }

    @Test(timeout = ENGINE_TEST_TIMEOUT_MILLIS)
    fun `a non-terminating call times out`() = runBlocking {
        withRuntime(QuickJsRuntime()) { runtime ->
            val sourceId = runtime.installSource(
                """
                function spin() { while (true) { } }
                function add(left, right) { return { sum: left + right }; }
                """.trimIndent(),
            )

            val result =
                runtime.invoke(
                    SourceCall.InvokeFunction(
                        callId = "spin-1",
                        sourceId = sourceId,
                        functionName = "spin",
                        timeoutMillis = 2_000,
                    ),
                )

            val error = (result as SourceResult.Failure).error
            assertTrue("expected Timeout, got $error", error is SourceRuntimeError.Timeout)
            assertSourceUsableAfterInterrupt(runtime, sourceId)
        }
    }

    @Test(timeout = ENGINE_TEST_TIMEOUT_MILLIS)
    fun `cancelling a call reports Cancelled`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val hostCancelled = CompletableDeferred<Unit>()
        val host = RecordingHostApi { _ ->
            entered.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                hostCancelled.complete(Unit)
            }
        }

        withRuntime(QuickJsRuntime(hostApi = host)) { runtime ->
            val sourceId = runtime.installSource(FETCH_SCRIPT)
            val pending =
                async {
                    runtime.invoke(
                        SourceCall.InvokeFunction(
                            callId = "slow-1",
                            sourceId = sourceId,
                            functionName = "search",
                            argumentsJson = """["slow"]""",
                            timeoutMillis = 30_000,
                        ),
                    )
                }

            entered.await()
            runtime.cancel("slow-1")
            val result = pending.await()

            val error = (result as SourceResult.Failure).error
            assertTrue("expected Cancelled, got $error", error is SourceRuntimeError.Cancelled)
            // SourceHostApi requires a cancelled invocation to cancel its child operations.
            assertTrue(
                "the host request should be cancelled with the call",
                withTimeoutOrNull(HOST_CANCELLATION_WAIT_MILLIS) { hostCancelled.await() } != null,
            )
        }
    }

    /**
     * The pinned binding cannot interrupt a script that is spinning inside the engine, so a timeout
     * leaves that engine unusable. The runtime must therefore rebuild it: this asserts the source
     * still answers after a timeout, which is the behaviour the runtime promises instead of
     * pretending the interrupted script is fine.
     */
    private suspend fun assertSourceUsableAfterInterrupt(runtime: QuickJsRuntime, sourceId: SourceId) {
        val followUp =
            withTimeoutOrNull(FOLLOW_UP_WAIT_MILLIS) {
                runCatching { runtime.invokeSuccess(sourceId, "add", "[1,2]") }
            }
        assertTrue("the source should answer again after a timeout", followUp?.isSuccess == true)
    }

    @Test
    fun `an unloaded source is no longer callable`() = runBlocking {
        withRuntime(QuickJsRuntime()) { runtime ->
            val sourceId = runtime.installSource(ECHO_SCRIPT)
            runtime.unload(sourceId)

            val result = runtime.invoke(SourceCall.InvokeFunction("after-1", sourceId, "add", "[1,2]"))

            val error = (result as SourceResult.Failure).error
            assertTrue("expected SourceNotLoaded, got $error", error is SourceRuntimeError.SourceNotLoaded)
        }
    }

    @Test
    fun `a package whose hash does not match is rejected`() = runBlocking {
        withRuntime(QuickJsRuntime()) { runtime ->
            val script = ECHO_SCRIPT
            val result =
                runtime.install(
                    SourcePackage(
                        sourceId = SourceId("mismatch"),
                        version = "1",
                        script = script,
                        sha256 = sha256("$script "),
                    ),
                )

            val failure = result as SourceInstallResult.Failed
            assertTrue(failure.error is SourceRuntimeError.InvalidPackage)
        }
    }

    @Test
    fun `a script that throws while loading is rejected`() = runBlocking {
        withRuntime(QuickJsRuntime()) { runtime ->
            val result = runtime.installSourceResult("""throw new Error("no init");""")

            val failure = result as SourceInstallResult.Failed
            assertTrue(
                "expected a package error, got ${failure.error}",
                failure.error is SourceRuntimeError.InvalidPackage,
            )
        }
    }

    @Test
    fun `a script with a syntax error is reported as such`() = runBlocking {
        withRuntime(QuickJsRuntime()) { runtime ->
            val result = runtime.installSourceResult("""function broken( { """)

            val failure = result as SourceInstallResult.Failed
            assertTrue(
                "expected ScriptSyntax, got ${failure.error}",
                failure.error is SourceRuntimeError.ScriptSyntax,
            )
        }
    }

    private suspend fun withRuntime(runtime: QuickJsRuntime, block: suspend (QuickJsRuntime) -> Unit) {
        try {
            block(runtime)
        } finally {
            runtime.close()
        }
    }

    private suspend fun SourceScriptRuntime.installSource(script: String): SourceId =
        (installSourceResult(script) as SourceInstallResult.Installed).sourceId

    private suspend fun SourceScriptRuntime.installSourceResult(
        script: String,
    ): SourceInstallResult =
        install(
            SourcePackage(
                sourceId = SourceId("fixture"),
                version = "1",
                script = script,
                sha256 = sha256(script),
            ),
        )

    private suspend fun SourceScriptRuntime.invokeSuccess(
        sourceId: SourceId,
        functionName: String,
        argumentsJson: String,
    ): String {
        val result = invoke(SourceCall.InvokeFunction(functionName + "-1", sourceId, functionName, argumentsJson))
        assertTrue("expected success, got $result", result is SourceResult.Success)
        return (result as SourceResult.Success).json
    }

    private class RecordingHostApi(
        private val respond: suspend (SourceHostRequest) -> SourceHostResult,
    ) : SourceHostApi {
        val requests = CopyOnWriteArrayList<SourceHostRequest>()

        override fun isMethodAllowed(method: String): Boolean = method == "http.request"

        override suspend fun invoke(request: SourceHostRequest): SourceHostResult {
            requests += request
            return respond(request)
        }
    }

    private companion object {
        const val ENGINE_TEST_TIMEOUT_MILLIS = 120_000L
        const val FOLLOW_UP_WAIT_MILLIS = 15_000L
        const val HOST_CANCELLATION_WAIT_MILLIS = 10_000L

        val ECHO_SCRIPT = """function add(left, right) { return { sum: left + right }; }"""

        val FETCH_SCRIPT =
            """
            async function search(keyword) {
              const response = await fetch(
                "https://example.com/search?q=" + encodeURIComponent(keyword)
              );
              const data = await response.json();
              return { ok: response.ok, status: response.status, count: data.items.length };
            }
            """.trimIndent()

        val FORM_SCRIPT =
            """
            async function submit(text) {
              const response = await fetch("https://example.com/post", {
                method: "POST",
                headers: { "Content-Type": "text/plain; charset=utf-8" },
                body: Convert.encodeUtf8(text)
              });
              return { status: response.status };
            }
            """.trimIndent()

        fun sha256(value: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
                .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
