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
import kotlinx.coroutines.delay
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
 * Fixtures follow the upstream source convention — `class X extends ComicSource` with members
 * reached by path — so these tests fail if the runtime drifts away from what real sources expect.
 *
 * `runBlocking` rather than `runTest` on purpose: timeout and cancellation must be measured against
 * real engine work, and the test scheduler's virtual clock would fire timeouts before the engine has
 * done anything.
 */
class QuickJsRuntimeTest {

    @Test
    fun `a source instance is created, registered and callable`() = runBlocking {
        withRuntime(QuickJsRuntime()) { runtime ->
            val sourceId = runtime.installSource(
                fixtureSource(
                    """
                    registryCheck() {
                      return { registered: ComicSource.sources[this.key] === this };
                    }
                    """.trimIndent(),
                ),
            )

            val result = JSONObject(runtime.invokeSuccess(sourceId, "registryCheck", "[]"))

            assertTrue("the instance should be the registry entry", result.getBoolean("registered"))
        }
    }

    @Test
    fun `a member is called on the object that declares it`() = runBlocking {
        withRuntime(QuickJsRuntime()) { runtime ->
            val sourceId = runtime.installSource(
                fixtureSource(
                    """
                    counter = {
                      value: 0,
                      bump: function () {
                        this.value += 1;
                        return this.value;
                      }
                    };
                    """.trimIndent(),
                ),
            )

            // The counter lives on the declaring object, so the second call must see the first
            // call's effect. A runtime that called the member with `this = globalThis` would reset
            // it and return 1 twice.
            assertEquals("1", runtime.invokeSuccess(sourceId, "counter.bump"))
            assertEquals("2", runtime.invokeSuccess(sourceId, "counter.bump"))
        }
    }

    @Test
    fun `init runs while the source is installed`() = runBlocking {
        withRuntime(QuickJsRuntime()) { runtime ->
            val sourceId = runtime.installSource(
                fixtureSource(
                    """
                    init() {
                      this.initialised = true;
                    }
                    wasInitialised() {
                      return { value: this.initialised === true };
                    }
                    """.trimIndent(),
                ),
            )

            val result = JSONObject(runtime.invokeSuccess(sourceId, "wasInitialised", "[]"))

            assertTrue(result.getBoolean("value"))
        }
    }

    @Test
    fun `init host calls carry an installation invocation id`() = runBlocking {
        val host = RecordingHostApi { request -> respondWithBody(request.requestId, 200, "{}") }
        withRuntime(QuickJsRuntime(hostApi = host)) { runtime ->
            runtime.installSource(
                fixtureSource(
                    """
                    async init() { await fetch("https://example.com/init"); }
                    known() { return true; }
                    """.trimIndent(),
                ),
            )
        }
        assertEquals("source-install", host.requests.single().invocationId)
    }

    @Test
    fun `the declared settings default answers loadSetting`() = runBlocking {
        withRuntime(QuickJsRuntime()) { runtime ->
            val sourceId = runtime.installSource(
                fixtureSource(
                    """
                    settings = { quality: { default: "high" } };
                    quality() {
                      return { value: this.loadSetting("quality") };
                    }
                    """.trimIndent(),
                ),
            )

            val result = JSONObject(runtime.invokeSuccess(sourceId, "quality", "[]"))

            assertEquals("high", result.getString("value"))
        }
    }

    @Test
    fun `a source awaits fetch and reads a json body`() = runBlocking {
        val recordedUrls = CopyOnWriteArrayList<String>()
        val host = RecordingHostApi { request ->
            val payload = JSONObject(request.payloadJson)
            recordedUrls += payload.getString("url")
            respondWithBody(request.requestId, 200, """{"items":[7,8]}""")
        }

        withRuntime(QuickJsRuntime(hostApi = host)) { runtime ->
            val sourceId = runtime.installSource(
                fixtureSource(
                    """
                    search = {
                      load: async function (keyword) {
                        const response = await fetch(
                          "https://example.com/search?q=" + encodeURIComponent(keyword)
                        );
                        const data = await response.json();
                        return { ok: response.ok, status: response.status, count: data.items.length };
                      }
                    };
                    """.trimIndent(),
                ),
            )

            val result = runtime.invokeSuccess(sourceId, "search.load", """["cats and dogs"]""")
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
            respondWithBody(request.requestId, 201, "created")
        }

        withRuntime(QuickJsRuntime(hostApi = host)) { runtime ->
            val sourceId = runtime.installSource(
                fixtureSource(
                    """
                    comic = {
                      loadEp: async function (id, ep) {
                        const response = await fetch("https://example.com/comment", {
                          method: "POST",
                          headers: { "Content-Type": "text/plain; charset=utf-8" },
                          body: Convert.encodeUtf8(id + "=" + ep)
                        });
                        return { status: response.status };
                      }
                    };
                    """.trimIndent(),
                ),
            )

            val result = runtime.invokeSuccess(sourceId, "comic.loadEp", """["标题","中文 ✓"]""")

            assertEquals(201, JSONObject(result).getInt("status"))
            assertEquals(listOf("标题=中文 ✓"), sentBodies)
        }
    }

    @Test
    fun `console output is redacted before it reaches the host log`() = runBlocking {
        val logs = CopyOnWriteArrayList<Pair<String, String>>()
        val runtime = QuickJsRuntime(logSink = { level, message -> logs += level to message })

        withRuntime(runtime) { active ->
            val sourceId = active.installSource(
                fixtureSource(
                    """
                    shout() {
                      console.warn("Authorization: secret-token", { password: "hidden" });
                      return "done";
                    }
                    """.trimIndent(),
                ),
            )
            active.invokeSuccess(sourceId, "shout", "[]")
        }

        assertEquals(1, logs.size)
        assertEquals("warn", logs.single().first)
        assertEquals(QuickJsHostBridge.REDACTED_LOG_MESSAGE, logs.single().second)
    }

    @Test
    fun `the app locale is exposed to sources`() = runBlocking {
        withRuntime(QuickJsRuntime(appLocale = "zh_CN")) { runtime ->
            val sourceId = runtime.installSource(
                fixtureSource("""locale() { return APP.locale; }"""),
            )

            assertEquals("\"zh_CN\"", runtime.invokeSuccess(sourceId, "locale", "[]"))
        }
    }

    @Test
    fun `an unknown member fails without killing the source`() = runBlocking {
        withRuntime(QuickJsRuntime()) { runtime ->
            val sourceId = runtime.installSource(fixtureSource("""known() { return 1; }"""))

            val result = runtime.invoke(SourceCall.InvokeFunction("unknown-1", sourceId, "search.load"))

            val error = (result as SourceResult.Failure).error
            assertTrue("expected ScriptExecution, got $error", error is SourceRuntimeError.ScriptExecution)
            assertTrue(error.message.contains("Unknown source member"))
        }
    }

    @Test
    fun `a source failure becomes a script execution error`() = runBlocking {
        withRuntime(QuickJsRuntime()) { runtime ->
            val sourceId = runtime.installSource(
                fixtureSource("""fail() { throw new Error("boom"); }"""),
            )
            val result = runtime.invoke(SourceCall.InvokeFunction("fail-1", sourceId, "fail"))

            val error = (result as SourceResult.Failure).error
            assertTrue("expected ScriptExecution, got $error", error is SourceRuntimeError.ScriptExecution)
            assertTrue(error.message.contains("boom"))
        }
    }

    @Test
    fun `a host method outside the allow list is rejected`() = runBlocking {
        val host = RecordingHostApi { request -> respondWithBody(request.requestId, 200, "{}") }

        withRuntime(QuickJsRuntime(hostApi = host)) { runtime ->
            val sourceId = runtime.installSource(
                fixtureSource(
                    """read() { return veneraHost.call("files.read", { path: "/etc" }); }""",
                ),
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
                fixtureSource(
                    """
                    spin() { while (true) { } }
                    add(left, right) { return { sum: left + right }; }
                    """.trimIndent(),
                ),
            )

            val startedAt = System.nanoTime()
            val result =
                runtime.invoke(
                    SourceCall.InvokeFunction(
                        callId = "spin-1",
                        sourceId = sourceId,
                        functionName = "spin",
                        timeoutMillis = 2_000,
                    ),
                )
            val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L

            val error = (result as SourceResult.Failure).error
            assertTrue("expected Timeout, got $error", error is SourceRuntimeError.Timeout)
            assertTrue(
                "interrupting a busy JavaScript evaluation should finish promptly (took ${elapsedMillis}ms)",
                elapsedMillis < 2_800,
            )
            assertSourceUsableAfterInterrupt(runtime, sourceId)
        }
    }

    @Test(timeout = ENGINE_TEST_TIMEOUT_MILLIS)
    fun `cancelling an in-engine call interrupts evaluation`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val host = RecordingHostApi { request ->
            entered.complete(Unit)
            respondWithBody(request.requestId, 200, "{}")
        }
        withRuntime(QuickJsRuntime(hostApi = host)) { runtime ->
            val sourceId = runtime.installSource(
                fixtureSource(
                    """
                    spin = {
                      load: async function () {
                        await fetch("https://example.com/before-spin");
                        while (true) { }
                      }
                    };
                    add(left, right) { return { sum: left + right }; }
                    """.trimIndent(),
                ),
            )
            val pending = async {
                runtime.invoke(
                    SourceCall.InvokeFunction(
                        callId = "spin-cancel",
                        sourceId = sourceId,
                        functionName = "spin.load",
                        timeoutMillis = 30_000,
                    ),
                )
            }

            entered.await()
            // Let the resolved host call return into JavaScript before cancelling the CPU-bound loop.
            delay(100)
            val cancellationStartedAt = System.nanoTime()
            runtime.cancel("spin-cancel")
            val result = withTimeoutOrNull(FOLLOW_UP_WAIT_MILLIS) { pending.await() }
            val cancellationElapsedMillis =
                (System.nanoTime() - cancellationStartedAt) / 1_000_000L

            assertTrue("the in-engine cancellation should return promptly", result != null)
            assertTrue(
                "in-engine cancellation should not exhaust the interrupt grace period " +
                    "(took ${cancellationElapsedMillis}ms)",
                cancellationElapsedMillis < 800,
            )
            val error = (result as SourceResult.Failure).error
            assertTrue("expected Cancelled, got $error", error is SourceRuntimeError.Cancelled)
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
            val sourceId = runtime.installSource(fetchSource())
            val pending =
                async {
                    runtime.invoke(
                        SourceCall.InvokeFunction(
                            callId = "slow-1",
                            sourceId = sourceId,
                            functionName = "search.load",
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

    @Test(timeout = ENGINE_TEST_TIMEOUT_MILLIS)
    fun `a second call is rejected instead of waiting in an unbounded queue`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val host = RecordingHostApi { request ->
            entered.complete(Unit)
            awaitCancellation()
        }
        withRuntime(QuickJsRuntime(hostApi = host)) { runtime ->
            val sourceId = runtime.installSource(fetchSource())
            val first = async {
                runtime.invoke(SourceCall.InvokeFunction("first", sourceId, "search.load", "[\"slow\"]", 30_000))
            }
            entered.await()
            val second = runtime.invoke(SourceCall.InvokeFunction("second", sourceId, "search.load", "[\"next\"]"))
            val error = (second as SourceResult.Failure).error
            assertTrue(error is SourceRuntimeError.Internal && error.retryable)
            runtime.cancel("first")
            first.await()
        }
    }

    @Test
    fun `an unloaded source is no longer callable`() = runBlocking {
        withRuntime(QuickJsRuntime()) { runtime ->
            val sourceId = runtime.installSource(fixtureSource("""known() { return 1; }"""))
            runtime.unload(sourceId)

            val result = runtime.invoke(SourceCall.InvokeFunction("after-1", sourceId, "known"))

            val error = (result as SourceResult.Failure).error
            assertTrue("expected SourceNotLoaded, got $error", error is SourceRuntimeError.SourceNotLoaded)
        }
    }

    @Test
    fun `a package whose hash does not match is rejected`() = runBlocking {
        withRuntime(QuickJsRuntime()) { runtime ->
            val script = fixtureSource("""known() { return 1; }""")
            val result =
                runtime.install(
                    SourcePackage(
                        sourceId = SourceId("fixture"),
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
    fun `a script that declares another key is rejected`() = runBlocking {
        withRuntime(QuickJsRuntime()) { runtime ->
            val script = fixtureSource("""known() { return 1; }""", key = "something_else")
            val result =
                runtime.install(
                    SourcePackage(
                        sourceId = SourceId("fixture"),
                        version = "1",
                        script = script,
                        sha256 = sha256(script),
                    ),
                )

            val failure = result as SourceInstallResult.Failed
            assertTrue("expected InvalidPackage, got ${failure.error}", failure.error is SourceRuntimeError.InvalidPackage)
            assertTrue(failure.error.message.contains("something_else"))
        }
    }

    @Test
    fun `a script that does not follow the class convention is rejected`() = runBlocking {
        withRuntime(QuickJsRuntime()) { runtime ->
            val result = runtime.installSourceResult("""function add(left, right) { return left + right; }""")

            val failure = result as SourceInstallResult.Failed
            assertTrue(
                "expected a package error, got ${failure.error}",
                failure.error is SourceRuntimeError.InvalidPackage,
            )
        }
    }

    @Test
    fun `an unusable key is rejected`() = runBlocking {
        withRuntime(QuickJsRuntime()) { runtime ->
            val result =
                runtime.installSourceResult(
                    fixtureSource("""known() { return 1; }""", key = "not-a-key"),
                )

            val failure = result as SourceInstallResult.Failed
            assertTrue("expected a package error, got ${failure.error}", failure.error is SourceRuntimeError.InvalidPackage)
        }
    }

    @Test
    fun `a script that throws while loading is rejected`() = runBlocking {
        withRuntime(QuickJsRuntime()) { runtime ->
            val script =
                """
                class BrokenSource extends ComicSource {
                  constructor() {
                    super();
                    this.key = "fixture";
                    this.name = "Broken";
                    this.version = "1";
                    throw new Error("no init");
                  }
                }
                """.trimIndent()
            val result = runtime.installSourceResult(script)

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
            val result =
                runtime.installSourceResult(
                    """
                    class BrokenSource extends ComicSource {
                      constructor() {
                        super();
                        this.key = "fixture";
                    }
                    """.trimIndent(),
                )

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

    private suspend fun SourceScriptRuntime.installSource(script: String): SourceId {
        val result = installSourceResult(script)
        assertTrue("expected install, got $result", result is SourceInstallResult.Installed)
        return (result as SourceInstallResult.Installed).sourceId
    }

    private suspend fun SourceScriptRuntime.installSourceResult(
        script: String,
    ): SourceInstallResult =
        install(
            SourcePackage(
                sourceId = SourceId(FIXTURE_KEY),
                version = "1",
                script = script,
                sha256 = sha256(script),
            ),
        )

    private suspend fun SourceScriptRuntime.invokeSuccess(
        sourceId: SourceId,
        member: String,
        argumentsJson: String = "[]",
    ): String {
        val result =
            invoke(SourceCall.InvokeFunction(member + "-call", sourceId, member, argumentsJson))
        assertTrue("expected success, got $result", result is SourceResult.Success)
        return (result as SourceResult.Success).json
    }

    /** A timed-out source stays installed and can answer again after its interrupted engine rebuilds. */
    private suspend fun assertSourceUsableAfterInterrupt(runtime: QuickJsRuntime, sourceId: SourceId) {
        val followUp =
            withTimeoutOrNull(FOLLOW_UP_WAIT_MILLIS) {
                runCatching {
                    runtime.invokeSuccess(sourceId, "add", "[1,2]")
                }
            }
        assertTrue("the source should answer again after a timeout", followUp?.isSuccess == true)
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
        const val FIXTURE_KEY = "fixture"

        /**
         * A source script shaped the way upstream sources are, with [body] as extra members.
         *
         * Built line by line rather than interpolated into one indented raw string: the body brings
         * its own indentation, which would make `trimIndent` a no-op and leave the class declaration
         * indented — and an indented declaration is rejected by the upstream convention.
         */
        fun fixtureSource(body: String, key: String = FIXTURE_KEY): String =
            buildString {
                appendLine("class FixtureSource extends ComicSource {")
                appendLine("  constructor() {")
                appendLine("    super();")
                appendLine("    this.name = \"Fixture\";")
                appendLine("    this.key = \"$key\";")
                appendLine("    this.version = \"1\";")
                appendLine("  }")
                appendLine()
                appendLine(body)
                appendLine("}")
            }

        fun fetchSource(): String = fixtureSource(
            """
            search = {
              load: async function (keyword) {
                const response = await fetch("https://example.com/search?q=" + keyword);
                return { status: response.status };
              }
            };
            """.trimIndent(),
        )

        fun respondWithBody(requestId: String, statusCode: Int, body: String): SourceHostResult =
            SourceHostResult.Success(
                requestId = requestId,
                resultJson =
                    JSONObject()
                        .put("statusCode", statusCode)
                        .put(
                            "headers",
                            JSONObject().put("Content-Type", JSONArray().put("application/json")),
                        )
                        .put("body", body)
                        .toString(),
            )

        fun sha256(value: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
                .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
