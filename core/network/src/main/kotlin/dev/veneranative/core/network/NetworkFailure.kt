package dev.veneranative.core.network

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ProtocolException

sealed interface NetworkFailure {
    data object Cancelled : NetworkFailure
    data object Timeout : NetworkFailure
    data object Protocol : NetworkFailure
    data object Connection : NetworkFailure
}

object NetworkFailureMapper {
    fun from(failure: IOException, cancelled: Boolean): NetworkFailure =
        when {
            // OkHttp's total call timeout cancels the Call and reports InterruptedIOException.
            // Check timeout first so it remains retryable instead of looking like user cancel.
            failure is InterruptedIOException -> NetworkFailure.Timeout
            cancelled -> NetworkFailure.Cancelled
            failure is ProtocolException -> NetworkFailure.Protocol
            else -> NetworkFailure.Connection
        }
}
