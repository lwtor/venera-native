package dev.veneranative.data.comic

import dev.veneranative.source.api.SourceOutcome
import dev.veneranative.source.api.SourceRuntimeError
import kotlinx.coroutines.delay

/** Only for read operations: scripts can surface temporary HTTP failures as ScriptExecution. */
suspend fun <T> retrySourceRead(
    wait: suspend (Int) -> Unit = { delay(it * 500L) },
    call: suspend () -> SourceOutcome<T>,
): SourceOutcome<T> {
    repeat(3) { attempt ->
        val result = call()
        if (result is SourceOutcome.Success) return result
        val error = (result as SourceOutcome.Failure).error
        if (attempt == 2 || error is SourceRuntimeError.Cancelled ||
            (!error.retryable && error !is SourceRuntimeError.ScriptExecution)) return result
        wait(attempt + 1)
    }
    error("Unreachable retry state")
}
