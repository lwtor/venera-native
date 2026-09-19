package dev.veneranative.source.api

import dev.veneranative.core.model.SourceId

/**
 * Engine-independent allow-listed API exposed to a source script.
 *
 * Implementations must be cancellable: cancelling [invoke] must cancel any child operation.
 */
interface SourceHostApi {
    fun isMethodAllowed(method: String): Boolean

    suspend fun invoke(request: SourceHostRequest): SourceHostResult
}

data class SourceHostRequest(
    val sourceId: SourceId,
    val invocationId: String,
    val requestId: String,
    val method: String,
    val payloadJson: String,
)

sealed interface SourceHostResult {
    val requestId: String

    data class Success(
        override val requestId: String,
        val resultJson: String,
    ) : SourceHostResult

    data class Failure(
        override val requestId: String,
        val error: SourceHostError,
    ) : SourceHostResult
}

data class SourceHostError(
    val code: Code,
    val message: String,
    val retryable: Boolean,
) {
    enum class Code {
        METHOD_NOT_ALLOWED,
        INVALID_REQUEST,
        NETWORK_TIMEOUT,
        NETWORK_CONNECTION,
        CONCURRENCY_LIMIT,
        RESPONSE_TOO_LARGE,
        CANCELLED,
        INTERNAL,
    }
}
