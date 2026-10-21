package dev.veneranative.source.core

import dev.veneranative.core.model.SourceId
import dev.veneranative.source.api.*
import dev.veneranative.source.api.protocol.SourceOperationKind
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SourceCallExecutorTest {
    @Test fun `successful calls persist the source data snapshot`() = runTest {
        val sourceId = SourceId("fixture")
        val runtime = object : StubRuntime() {
            override suspend fun invoke(call: SourceCall): SourceResult =
                SourceResult.Success(call.callId, "true", "{\"token\":\"encrypted-at-rest\"}")
        }
        val snapshots = mutableListOf<Pair<SourceId, String>>()
        val executor = SourceCallExecutor(runtime, onSourceDataChanged = { id, data -> snapshots += id to data })

        val result = executor.execute(SourceOperationKind.WRITE) {
            SourceCall.InvokeFunction("login", sourceId, "account.login")
        }

        assertTrue(result is SourceResult.Success)
        assertEquals(listOf(sourceId to "{\"token\":\"encrypted-at-rest\"}"), snapshots)
    }

    @Test fun `all reads share retries with fresh call IDs while writes and cancelled calls do not retry`() = runTest {
        for ((kind, error, expected) in listOf(
            Triple(SourceOperationKind.READ, SourceRuntimeError.ScriptExecution("fixture"), 3),
            Triple(SourceOperationKind.READ, SourceRuntimeError.Busy(), 3),
            Triple(SourceOperationKind.WRITE, SourceRuntimeError.Timeout(1), 1),
            Triple(SourceOperationKind.READ, SourceRuntimeError.Cancelled(), 1),
            Triple(SourceOperationKind.READ, SourceRuntimeError.InvalidCall("fixture"), 1),
        )) {
            val calls = mutableListOf<String>()
            val runtime = object : StubRuntime() {
                override suspend fun invoke(call: SourceCall): SourceResult {
                    calls += call.callId
                    return SourceResult.Failure(call.callId, error)
                }
            }
            var sequence = 0
            SourceCallExecutor(runtime, wait = {}).execute(kind) {
                SourceCall.InvokeFunction("call-${++sequence}", SourceId("fixture"), "read")
            }
            assertEquals(expected, calls.size)
            assertEquals(expected, calls.distinct().size)
        }
    }

    @Test fun `queue or suspended invocation has a bounded overall deadline`() = runTest {
        val runtime = object : StubRuntime() {
            override suspend fun invoke(call: SourceCall): SourceResult = kotlinx.coroutines.awaitCancellation()
        }
        val result = SourceCallExecutor(runtime, totalTimeoutMillis = 100).execute(SourceOperationKind.READ) {
            SourceCall.InvokeFunction("hung", SourceId("fixture"), "read")
        }
        assertTrue((result as SourceResult.Failure).error is SourceRuntimeError.Timeout)
    }

    @Test fun `central retry recreates a script instance poisoned by the first failed request`() = kotlinx.coroutines.runBlocking {
        var requests = 0
        val host = object : SourceHostApi {
            override fun isMethodAllowed(method: String) = method == "http.request"
            override suspend fun invoke(request: SourceHostRequest): SourceHostResult {
                val status = if (++requests == 1) 503 else 200
                return SourceHostResult.Success(request.requestId, """{"statusCode":$status,"headers":{},"body":"fixture"}""")
            }
        }
        val runtime = dev.veneranative.source.engine.QuickJsRuntime(hostApi = host)
        val script = """
            class Fixture extends ComicSource {
                name = 'Fixture'; key = 'fixture'; version = '1';
                async read() {
                    if (this.dirty) throw new Error('stale instance');
                    this.dirty = true;
                    const response = await Network.get('https://fixture.invalid/metadata', {});
                    if (response.status !== 200) throw new Error('temporary failure');
                    return 'ready';
                }
            }
        """.trimIndent()
        try {
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(script.toByteArray()).joinToString("") { "%02x".format(it) }
            val id = SourceId("fixture")
            assertTrue(runtime.install(SourcePackage(id, "1", script, digest)) is SourceInstallResult.Installed)
            var sequence = 0
            val result = SourceCallExecutor(runtime, wait = {}).execute(SourceOperationKind.READ) {
                SourceCall.InvokeFunction("recover-${++sequence}", id, "read")
            }
            assertEquals("\"ready\"", (result as SourceResult.Success).json)
            assertEquals(2, requests)
        } finally { runtime.close() }
    }

    private abstract class StubRuntime : SourceScriptRuntime {
        override fun isSupported() = true
        override suspend fun install(source: SourcePackage) = SourceInstallResult.Installed(source.sourceId, source.version)
        override suspend fun cancel(callId: String) = Unit
        override suspend fun unload(sourceId: SourceId) = Unit
        override suspend fun close() = Unit
    }
}
