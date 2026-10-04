package dev.veneranative.source.network

import dev.veneranative.source.api.SourceHostApi
import dev.veneranative.source.api.SourceHostError
import dev.veneranative.source.api.SourceHostRequest
import dev.veneranative.source.api.SourceHostResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

class SourceNetworkHostApi(
    private val executor: SourceNetworkExecutor,
    private val json: Json = Json,
    private val waitForTimer: suspend (Long) -> Unit = { delay(it) },
) : SourceHostApi {
    private val timers = ConcurrentHashMap<String, Job>()
    private val timerLock = Any()

    override fun isMethodAllowed(method: String): Boolean = method in ALLOWED_METHODS

    override suspend fun invoke(request: SourceHostRequest): SourceHostResult {
        when (request.method) {
            TIMER_SLEEP_METHOD -> return sleep(request)
            TIMER_CANCEL_METHOD -> return cancelTimer(request)
        }
        if (!isMethodAllowed(request.method)) {
            return request.failure(
                SourceHostError.Code.METHOD_NOT_ALLOWED,
                "Host method is not allowed.",
                retryable = false,
            )
        }
        if (request.payloadJson.length > MAX_REQUEST_PAYLOAD_CHARS) {
            return request.invalidRequest()
        }

        val httpRequest =
            try {
                decodeRequest(request.payloadJson)
            } catch (_: IllegalArgumentException) {
                return request.invalidRequest()
            } catch (_: RuntimeException) {
                return request.invalidRequest()
            }

        return when (val result = executor.execute(request.sourceId, httpRequest)) {
            is SourceHttpResult.Success ->
                SourceHostResult.Success(
                    requestId = request.requestId,
                    resultJson = encodeResponse(result.response),
                )
            is SourceHttpResult.Failure -> request.failure(result.error)
        }
    }

    private suspend fun sleep(request: SourceHostRequest): SourceHostResult {
        val payload = runCatching { json.parseToJsonElement(request.payloadJson).jsonObject }.getOrNull()
        val timerId = payload?.get("timerId")?.jsonPrimitive?.contentOrNull
        val delayMillis = payload?.get("delayMillis")?.jsonPrimitive?.longOrNull
        if (timerId.isNullOrBlank() || delayMillis == null || delayMillis !in 0..MAX_TIMER_DELAY_MILLIS) {
            return request.failure(
                SourceHostError.Code.INVALID_REQUEST,
                "Invalid timer request.",
                retryable = false,
            )
        }
        val key = timerKey(request, timerId)
        val job = coroutineContext[Job]
            ?: return request.failure(SourceHostError.Code.INTERNAL, "Timer context unavailable.", false)
        val registrationFailure = synchronized(timerLock) {
            val prefix = "${request.sourceId.value}:${request.invocationId}:"
            if (timers.keys.count { it.startsWith(prefix) } >= MAX_ACTIVE_TIMERS_PER_INVOCATION) {
                "Too many active timers."
            } else if (timers.putIfAbsent(key, job) != null) {
                "Duplicate timer id."
            } else {
                null
            }
        }
        if (registrationFailure != null) {
            return request.failure(SourceHostError.Code.INVALID_REQUEST, registrationFailure, false)
        }
        return try {
            waitForTimer(delayMillis)
            SourceHostResult.Success(request.requestId, "{}")
        } finally {
            synchronized(timerLock) { timers.remove(key, job) }
        }
    }

    private fun cancelTimer(request: SourceHostRequest): SourceHostResult {
        val timerId = runCatching {
            json.parseToJsonElement(request.payloadJson).jsonObject["timerId"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        if (timerId.isNullOrBlank()) {
            return request.failure(SourceHostError.Code.INVALID_REQUEST, "Invalid timer request.", false)
        }
        synchronized(timerLock) { timers.remove(timerKey(request, timerId)) }?.cancel()
        return SourceHostResult.Success(request.requestId, "{}")
    }

    private fun timerKey(request: SourceHostRequest, timerId: String) =
        "${request.sourceId.value}:${request.invocationId}:$timerId"

    private fun decodeRequest(payloadJson: String): SourceHttpRequest {
        val payload = json.parseToJsonElement(payloadJson).jsonObject
        val url = payload.requiredString("url")
        val method =
            when (payload.requiredString("method").uppercase()) {
                "GET" -> SourceHttpRequest.Method.GET
                "POST" -> SourceHttpRequest.Method.POST
                else -> throw IllegalArgumentException("Unsupported method.")
            }
        val headers =
            payload["headers"]?.jsonObject?.mapValues { (_, value) ->
                value.jsonPrimitive.content
            }.orEmpty()
        val body = payload["body"]?.jsonPrimitive?.contentOrNull
        return SourceHttpRequest(url = url, method = method, headers = headers, body = body)
    }

    private fun encodeResponse(response: SourceHttpResponse): String =
        buildJsonObject {
            put("statusCode", JsonPrimitive(response.statusCode))
            put(
                "headers",
                JsonObject(
                    response.headers.mapValues { (_, values) ->
                        JsonArray(values.map(::JsonPrimitive))
                    },
                ),
            )
            put("body", JsonPrimitive(response.body))
        }.toString()

    private fun SourceHostRequest.failure(error: SourceNetworkError): SourceHostResult.Failure =
        when (error) {
            SourceNetworkError.InvalidRequest -> invalidRequest()
            SourceNetworkError.Timeout ->
                failure(SourceHostError.Code.NETWORK_TIMEOUT, "Network request timed out.", true)
            is SourceNetworkError.Connection ->
                failure(SourceHostError.Code.NETWORK_CONNECTION, error.detail, true)
            SourceNetworkError.ConcurrencyLimit ->
                failure(
                    SourceHostError.Code.CONCURRENCY_LIMIT,
                    "Source network concurrency limit reached.",
                    true,
                )
            is SourceNetworkError.ResponseTooLarge ->
                failure(
                    SourceHostError.Code.RESPONSE_TOO_LARGE,
                    "Network response exceeded the allowed size.",
                    false,
                )
            SourceNetworkError.Cancelled ->
                failure(SourceHostError.Code.CANCELLED, "Network request was cancelled.", true)
        }

    private fun SourceHostRequest.invalidRequest(): SourceHostResult.Failure =
        failure(
            SourceHostError.Code.INVALID_REQUEST,
            "Invalid HTTP Host API request.",
            retryable = false,
        )

    private fun SourceHostRequest.failure(
        code: SourceHostError.Code,
        message: String,
        retryable: Boolean,
    ): SourceHostResult.Failure =
        SourceHostResult.Failure(
            requestId = requestId,
            error = SourceHostError(code, message, retryable),
        )

    private fun JsonObject.requiredString(name: String): String =
        get(name)?.jsonPrimitive?.content?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("Missing field.")

    companion object {
        const val HTTP_REQUEST_METHOD = "http.request"
        const val TIMER_SLEEP_METHOD = "timer.sleep"
        const val TIMER_CANCEL_METHOD = "timer.cancel"
        private const val MAX_REQUEST_PAYLOAD_CHARS = 262_144
        const val MAX_TIMER_DELAY_MILLIS = 120_000L
        private const val MAX_ACTIVE_TIMERS_PER_INVOCATION = 8
        private val ALLOWED_METHODS = setOf(HTTP_REQUEST_METHOD, TIMER_SLEEP_METHOD, TIMER_CANCEL_METHOD)
    }
}
