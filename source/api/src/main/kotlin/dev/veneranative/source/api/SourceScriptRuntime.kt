package dev.veneranative.source.api

import dev.veneranative.core.model.SourceId

/**
 * Engine-independent contract for installing and invoking comic-source scripts.
 *
 * Callers submit typed operations. JavaScript source code is only accepted as part of a
 * [SourcePackage], never as an invocation payload.
 */
interface SourceScriptRuntime {
    fun isSupported(): Boolean

    suspend fun install(source: SourcePackage): SourceInstallResult

    suspend fun invoke(call: SourceCall): SourceResult

    suspend fun cancel(callId: String)

    suspend fun unload(sourceId: SourceId)

    suspend fun close()
}

data class SourcePackage(
    val sourceId: SourceId,
    val version: String,
    val script: String,
    /** Lowercase or uppercase hexadecimal SHA-256 of [script] encoded as UTF-8. */
    val sha256: String,
)

sealed interface SourceInstallResult {
    data class Installed(
        val sourceId: SourceId,
        val version: String,
    ) : SourceInstallResult

    data class Failed(val error: SourceRuntimeError) : SourceInstallResult
}

sealed interface SourceCall {
    val callId: String
    val sourceId: SourceId
    val timeoutMillis: Long

    /**
     * Calls a function exported on the source global object.
     *
     * [argumentsJson] must be a JSON array. Results are returned as JSON in
     * [SourceResult.Success.json].
     */
    data class InvokeFunction(
        override val callId: String,
        override val sourceId: SourceId,
        val functionName: String,
        val argumentsJson: String = "[]",
        override val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    ) : SourceCall

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 10_000L
    }
}

sealed interface SourceResult {
    val callId: String

    data class Success(
        override val callId: String,
        val json: String,
    ) : SourceResult

    data class Failure(
        override val callId: String,
        val error: SourceRuntimeError,
    ) : SourceResult
}

sealed interface SourceRuntimeError {
    val message: String
    val retryable: Boolean

    data class EngineUnavailable(
        override val message: String = "JavaScript engine is not available on this device.",
    ) : SourceRuntimeError {
        override val retryable: Boolean = false
    }

    data class InvalidPackage(
        override val message: String,
    ) : SourceRuntimeError {
        override val retryable: Boolean = false
    }

    data class InvalidCall(
        override val message: String,
    ) : SourceRuntimeError {
        override val retryable: Boolean = false
    }

    data class SourceNotLoaded(
        val sourceId: SourceId,
        override val message: String = "Source is not loaded.",
    ) : SourceRuntimeError {
        override val retryable: Boolean = true
    }

    data class ScriptSyntax(
        override val message: String,
    ) : SourceRuntimeError {
        override val retryable: Boolean = false
    }

    data class ScriptExecution(
        override val message: String,
    ) : SourceRuntimeError {
        override val retryable: Boolean = false
    }

    data class Timeout(
        val timeoutMillis: Long,
        override val message: String = "Source call timed out.",
    ) : SourceRuntimeError {
        override val retryable: Boolean = true
    }

    data class Cancelled(
        override val message: String = "Source call was cancelled.",
    ) : SourceRuntimeError {
        override val retryable: Boolean = true
    }

    data class RuntimeClosed(
        override val message: String = "Source runtime is closed.",
    ) : SourceRuntimeError {
        override val retryable: Boolean = false
    }

    data class EngineTerminated(
        override val message: String,
    ) : SourceRuntimeError {
        override val retryable: Boolean = true
    }

    data class Internal(
        override val message: String,
    ) : SourceRuntimeError {
        override val retryable: Boolean = true
    }
}
