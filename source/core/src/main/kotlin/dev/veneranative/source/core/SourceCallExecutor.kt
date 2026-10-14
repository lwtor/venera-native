package dev.veneranative.source.core

import dev.veneranative.source.api.SourceCall
import dev.veneranative.source.api.SourceResult
import dev.veneranative.source.api.SourceRuntimeError
import dev.veneranative.source.api.SourceScriptRuntime
import dev.veneranative.source.api.protocol.SourceOperationKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** The sole Source Core execution entry; the runtime owns queueing and isolate recovery. */
internal class SourceCallExecutor(
    private val runtime: SourceScriptRuntime,
    private val wait: suspend (Int) -> Unit = { delay(it * 500L) },
    private val totalTimeoutMillis: Long = 120_000L,
) {
    suspend fun execute(kind: SourceOperationKind, newCall: () -> SourceCall.InvokeFunction): SourceResult {
        var call = newCall()
        if (kind == SourceOperationKind.WRITE) return runtime.invoke(call)
        return withTimeoutOrNull(totalTimeoutMillis) {
            for (attempt in 0..2) {
                if (attempt > 0) call = newCall()
                val result = runtime.invoke(call)
                if (result is SourceResult.Success) return@withTimeoutOrNull result
                val error = (result as SourceResult.Failure).error
                if (attempt == 2 || error is SourceRuntimeError.Cancelled ||
                    (!error.retryable && error !is SourceRuntimeError.ScriptExecution)) return@withTimeoutOrNull result
                wait(attempt + 1)
            }
            error("Unreachable retry state")
        } ?: SourceResult.Failure(call.callId, SourceRuntimeError.Timeout(totalTimeoutMillis))
    }
}
