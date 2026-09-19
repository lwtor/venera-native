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
import java.security.MessageDigest
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidJavaScriptRuntimeTest {
    private lateinit var context: Context
    private lateinit var runtime: AndroidJavaScriptRuntime
    private lateinit var fixtureScript: String

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        fixtureScript =
            context.assets.open("source_fixture.js").bufferedReader().use { reader ->
                reader.readText()
            }
        runtime = AndroidJavaScriptRuntime(context)
    }

    @After
    fun tearDown() = runBlocking {
        runtime.close()
    }

    @Test
    fun installsFixtureAndReturnsStructuredJson() = runBlocking {
        val sourceId = installFixture("basic")

        val result =
            runtime.invoke(
                SourceCall.InvokeFunction(
                    callId = "add",
                    sourceId = sourceId,
                    functionName = "add",
                    argumentsJson = "[2,3]",
                ),
            )

        assertTrue(result is SourceResult.Success)
        assertEquals(5, JSONObject((result as SourceResult.Success).json).getInt("sum"))
    }

    @Test
    fun sourceGlobalsAreIsolated() = runBlocking {
        val first = installFixture("first")
        val second = installFixture("second")

        assertEquals("1", invokeSuccess(first, "first-increment", "increment"))
        assertEquals("1", invokeSuccess(second, "second-increment", "increment"))
        assertEquals("2", invokeSuccess(first, "first-increment-again", "increment"))
    }

    @Test
    fun syntaxAndRuntimeFailuresUseStableErrors() = runBlocking {
        val invalidScript = "function broken( {"
        val installResult =
            runtime.install(
                SourcePackage(
                    sourceId = SourceId("invalid"),
                    version = "1",
                    script = invalidScript,
                    sha256 = sha256(invalidScript),
                ),
            )
        assertTrue(installResult is SourceInstallResult.Failed)
        assertTrue(
            (installResult as SourceInstallResult.Failed).error is SourceRuntimeError.ScriptSyntax,
        )

        val sourceId = installFixture("runtime-error")
        val callResult =
            runtime.invoke(
                SourceCall.InvokeFunction(
                    callId = "failure",
                    sourceId = sourceId,
                    functionName = "fail",
                ),
            )
        assertTrue(callResult is SourceResult.Failure)
        assertTrue(
            (callResult as SourceResult.Failure).error is SourceRuntimeError.ScriptExecution,
        )
    }

    @Test
    fun timeoutReplacesIsolateAndNextCallSucceeds() = runBlocking {
        val sourceId = installFixture("timeout")
        val timedOut =
            runtime.invoke(
                SourceCall.InvokeFunction(
                    callId = "timeout-call",
                    sourceId = sourceId,
                    functionName = "hang",
                    timeoutMillis = 100,
                ),
            )

        assertTrue(timedOut is SourceResult.Failure)
        assertTrue((timedOut as SourceResult.Failure).error is SourceRuntimeError.Timeout)
        val recovered = invokeSuccess(sourceId, "after-timeout", "add", "[4,5]")
        assertEquals(9, JSONObject(recovered).getInt("sum"))
    }

    @Test
    fun explicitCancellationDoesNotReturnSuccess() = runBlocking {
        val sourceId = installFixture("cancel")
        val pending =
            async {
                runtime.invoke(
                    SourceCall.InvokeFunction(
                        callId = "cancel-call",
                        sourceId = sourceId,
                        functionName = "hang",
                        timeoutMillis = 10_000,
                    ),
                )
            }

        delay(100)
        runtime.cancel("cancel-call")
        val cancelled = pending.await()

        assertTrue(cancelled is SourceResult.Failure)
        assertTrue((cancelled as SourceResult.Failure).error is SourceRuntimeError.Cancelled)
        val recovered = invokeSuccess(sourceId, "after-cancel", "echo", """["ready"]""")
        assertEquals("\"ready\"", recovered)
    }

    @Test
    fun unloadAndClosePreventFurtherCalls() = runBlocking {
        val sourceId = installFixture("lifecycle")
        runtime.unload(sourceId)

        val unloaded =
            runtime.invoke(
                SourceCall.InvokeFunction("unloaded", sourceId, "increment"),
            )
        assertTrue(unloaded is SourceResult.Failure)
        assertTrue((unloaded as SourceResult.Failure).error is SourceRuntimeError.SourceNotLoaded)

        runtime.close()
        val closed =
            runtime.invoke(
                SourceCall.InvokeFunction("closed", sourceId, "increment"),
            )
        assertTrue(closed is SourceResult.Failure)
        assertTrue((closed as SourceResult.Failure).error is SourceRuntimeError.RuntimeClosed)
    }

    @Test
    fun unavailableEngineReturnsCapabilityError() = runBlocking {
        val unavailableRuntime =
            AndroidJavaScriptRuntime(
                context = context,
                engineSupported = { false },
            )
        try {
            val result = unavailableRuntime.install(packageFor("unsupported"))
            assertTrue(result is SourceInstallResult.Failed)
            assertTrue(
                (result as SourceInstallResult.Failed).error is
                    SourceRuntimeError.EngineUnavailable,
            )
        } finally {
            unavailableRuntime.close()
        }
    }

    @Test
    fun invalidCallIsRejectedBeforeEvaluation() = runBlocking {
        val sourceId = installFixture("invalid-call")
        val result =
            runtime.invoke(
                SourceCall.InvokeFunction(
                    callId = "invalid-json",
                    sourceId = sourceId,
                    functionName = "echo",
                    argumentsJson = "{}",
                ),
            )

        assertTrue(result is SourceResult.Failure)
        assertTrue((result as SourceResult.Failure).error is SourceRuntimeError.InvalidCall)
    }

    private suspend fun installFixture(id: String): SourceId {
        val source = packageFor(id)
        val result = runtime.install(source)
        assertTrue(
            "Expected fixture source to install, got $result",
            result is SourceInstallResult.Installed,
        )
        return source.sourceId
    }

    private fun packageFor(id: String): SourcePackage =
        SourcePackage(
            sourceId = SourceId(id),
            version = "1",
            script = fixtureScript,
            sha256 = sha256(fixtureScript),
        )

    private suspend fun invokeSuccess(
        sourceId: SourceId,
        callId: String,
        functionName: String,
        argumentsJson: String = "[]",
    ): String {
        val result =
            runtime.invoke(
                SourceCall.InvokeFunction(
                    callId = callId,
                    sourceId = sourceId,
                    functionName = functionName,
                    argumentsJson = argumentsJson,
                ),
            )
        assertTrue("Expected success, got $result", result is SourceResult.Success)
        return (result as SourceResult.Success).json
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
}
