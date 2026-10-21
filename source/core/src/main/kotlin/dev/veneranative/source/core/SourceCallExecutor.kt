package dev.veneranative.source.core

import dev.veneranative.source.api.SourceCall
import dev.veneranative.source.api.SourceResult
import dev.veneranative.source.api.SourceRuntimeError
import dev.veneranative.source.api.SourceScriptRuntime
import dev.veneranative.core.model.SourceId
import dev.veneranative.source.api.protocol.SourceOperationKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** The sole Source Core execution entry; the runtime owns queueing and isolate recovery. */
internal class SourceCallExecutor(
    private val runtime: SourceScriptRuntime,
    private val wait: suspend (Int) -> Unit = { delay(it * 500L) },
    private val totalTimeoutMillis: Long = 120_000L,
    private val onSourceDataChanged: suspend (SourceId, String) -> Unit = { _, _ -> },
) {
    suspend fun execute(kind: SourceOperationKind, newCall: () -> SourceCall.InvokeFunction): SourceResult {
        var call = newCall()
        if (kind == SourceOperationKind.WRITE) return persistSourceData(call, runtime.invoke(call), required = true)
        return withTimeoutOrNull(totalTimeoutMillis) {
            for (attempt in 0..2) {
                if (attempt > 0) call = newCall()
                val result = runtime.invoke(call)
                if (result is SourceResult.Success) {
                    // A read has already produced useful comic data. A large or temporarily
                    // unwritable session snapshot must not turn a successful detail/search call
                    // into a user-visible source failure. Write operations (for example login)
                    // still report persistence errors because their main purpose is changing state.
                    return@withTimeoutOrNull persistSourceData(call, result, required = false)
                }
                val error = (result as SourceResult.Failure).error
                if (attempt == 2 || error is SourceRuntimeError.Cancelled ||
                    isPermanentContentFailure(error) ||
                    (!error.retryable && error !is SourceRuntimeError.ScriptExecution)) return@withTimeoutOrNull result
                wait(attempt + 1)
            }
            error("Unreachable retry state")
        } ?: SourceResult.Failure(call.callId, SourceRuntimeError.Timeout(totalTimeoutMillis))
    }

    /** A source may report a permanent content error as a JS exception; repeating it cannot help. */
    private fun isPermanentContentFailure(error: SourceRuntimeError): Boolean {
        val scriptError = error as? SourceRuntimeError.ScriptExecution ?: return false
        val message = scriptError.message.lowercase()
        return listOf("not found", "does not exist", "已被删除", "不存在").any(message::contains)
    }

    private suspend fun persistSourceData(
        call: SourceCall.InvokeFunction,
        result: SourceResult,
        required: Boolean,
    ): SourceResult {
        if (result !is SourceResult.Success) return result
        val sourceData = result.sourceDataJson ?: return result
        return try {
            onSourceDataChanged(call.sourceId, sourceData)
            result
        } catch (_: Exception) {
            if (required) {
                SourceResult.Failure(call.callId, SourceRuntimeError.Internal("Source session data could not be saved."))
            } else {
                result
            }
        }
    }
}
