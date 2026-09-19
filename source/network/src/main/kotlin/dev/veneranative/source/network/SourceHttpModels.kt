package dev.veneranative.source.network

data class SourceHttpRequest(
    val url: String,
    val method: Method,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
) {
    enum class Method {
        GET,
        POST,
    }
}

data class SourceHttpResponse(
    val statusCode: Int,
    val headers: Map<String, List<String>>,
    val body: String,
)

sealed interface SourceHttpResult {
    data class Success(val response: SourceHttpResponse) : SourceHttpResult
    data class Failure(val error: SourceNetworkError) : SourceHttpResult
}

sealed interface SourceNetworkError {
    val retryable: Boolean

    data object InvalidRequest : SourceNetworkError {
        override val retryable: Boolean = false
    }

    data object Timeout : SourceNetworkError {
        override val retryable: Boolean = true
    }

    data object Connection : SourceNetworkError {
        override val retryable: Boolean = true
    }

    data object ConcurrencyLimit : SourceNetworkError {
        override val retryable: Boolean = true
    }

    data class ResponseTooLarge(val maxBytes: Long) : SourceNetworkError {
        override val retryable: Boolean = false
    }

    data object Cancelled : SourceNetworkError {
        override val retryable: Boolean = true
    }
}
