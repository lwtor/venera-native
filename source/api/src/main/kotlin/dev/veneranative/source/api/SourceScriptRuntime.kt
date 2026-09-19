package dev.veneranative.source.api

import dev.veneranative.core.model.SourceId

interface SourceScriptRuntime {
    fun isSupported(): Boolean

    suspend fun install(source: SourcePackage): SourceInstallResult

    suspend fun invoke(call: SourceCall): SourceResult

    suspend fun cancel(callId: String)

    suspend fun unload(sourceId: SourceId)
}

data class SourcePackage(
    val sourceId: SourceId,
    val version: String,
    val script: String,
    val sha256: String,
)

sealed interface SourceInstallResult {
    data object Installed : SourceInstallResult
    data class Rejected(val reason: String) : SourceInstallResult
}

sealed interface SourceCall {
    val callId: String
    val sourceId: SourceId

    data class Evaluate(
        override val callId: String,
        override val sourceId: SourceId,
        val expression: String,
    ) : SourceCall
}

sealed interface SourceResult {
    data class Success(val json: String) : SourceResult
    data class Failure(val message: String, val retryable: Boolean) : SourceResult
}
