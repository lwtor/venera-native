package dev.veneranative.core.network

import java.io.IOException
import java.net.ProtocolException
import java.net.SocketTimeoutException

sealed interface NetworkFailure {
    data object Cancelled : NetworkFailure
    data object Timeout : NetworkFailure
    data object Protocol : NetworkFailure
    data object Connection : NetworkFailure
}

object NetworkFailureMapper {
    fun from(failure: IOException, cancelled: Boolean): NetworkFailure =
        when {
            cancelled -> NetworkFailure.Cancelled
            failure is SocketTimeoutException -> NetworkFailure.Timeout
            failure is ProtocolException -> NetworkFailure.Protocol
            else -> NetworkFailure.Connection
        }
}
