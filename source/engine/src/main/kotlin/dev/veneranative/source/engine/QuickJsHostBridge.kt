package dev.veneranative.source.engine

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.asyncFunction
import dev.veneranative.core.model.SourceId
import dev.veneranative.source.api.SourceHostApi
import dev.veneranative.source.api.SourceHostError
import dev.veneranative.source.api.SourceHostRequest
import dev.veneranative.source.api.SourceHostResult
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlin.text.Charsets.UTF_8
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Wires one engine instance to the host.
 *
 * Unlike the WebView bridge this needs no message port, no correlation table and no polling: the
 * engine calls a bound Kotlin function and gets the answer back on the same call (ADR-0008 §8).
 * What stays the same is the contract the script sees — `veneraHost.call(method, payload)` plus the
 * compatibility globals — and the allow-list enforcement, which happens here, not in JavaScript.
 */
internal class QuickJsHostBridge(
    private val sourceId: SourceId,
    private val hostApi: SourceHostApi?,
    appLocale: String,
    appVersion: String,
    private val maxMessageBytes: Int = DEFAULT_MAX_MESSAGE_BYTES,
    private val maxLogChars: Int = DEFAULT_MAX_LOG_CHARS,
    private val logSink: (String, String) -> Unit = DEFAULT_LOG_SINK,
) {
    private val sequence = AtomicLong(0L)
    private val bootstrapScript = QuickJsHostScript.bootstrap(
        appLocale = appLocale,
        appVersion = appVersion,
        maxLogChars = maxLogChars,
    )

    fun install(engine: QuickJs) {
        engine.asyncFunction<Any?>("__veneraHostCall") { arguments ->
            val method = arguments.getOrNull(0) as? String ?: ""
            val payloadJson = arguments.getOrNull(1) as? String ?: "{}"
            val invocationId = arguments.getOrNull(2) as? String ?: ""
            callHost(method = method, payloadJson = payloadJson, invocationId = invocationId)
        }
        engine.asyncFunction<Any?>("__veneraLog") { arguments ->
            val level = arguments.getOrNull(0) as? String ?: "log"
            val message = arguments.getOrNull(1) as? String ?: ""
            logSink(level, message)
        }
    }

    /**
     * The bootstrap must run before the source script: a source may call `fetch` while it loads.
     */
    suspend fun loadBootstrap(engine: QuickJs) {
        val ready = engine.evaluate<String>("$bootstrapScript\n;\"$READY_SENTINEL\";")
        check(ready == READY_SENTINEL) { "Host bridge did not initialize." }
    }

    private suspend fun callHost(
        method: String,
        payloadJson: String,
        invocationId: String,
    ): String {
        val api = hostApi
            ?: return encodeFailure(
                requestId = "",
                code = SourceHostError.Code.INTERNAL,
                message = "No host API is configured.",
                retryable = false,
            )
        val requestId = invocationId + ":" + sequence.incrementAndGet()

        if (!api.isMethodAllowed(method)) {
            return encodeFailure(
                requestId = requestId,
                code = SourceHostError.Code.METHOD_NOT_ALLOWED,
                message = "Host method is not allowed.",
                retryable = false,
            )
        }
        if (payloadJson.toByteArray(UTF_8).size > maxMessageBytes) {
            return encodeFailure(
                requestId = requestId,
                code = SourceHostError.Code.INVALID_REQUEST,
                message = "Host request exceeded the allowed size.",
                retryable = false,
            )
        }

        val result =
            try {
                api.invoke(
                    SourceHostRequest(
                        sourceId = sourceId,
                        invocationId = invocationId,
                        requestId = requestId,
                        method = method,
                        payloadJson = payloadJson,
                    ),
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                return encodeFailure(
                    requestId = requestId,
                    code = SourceHostError.Code.INTERNAL,
                    message = "Host request failed.",
                    retryable = true,
                )
            }

        val encoded = encode(result)
        return if (encoded.toByteArray(UTF_8).size <= maxMessageBytes) {
            encoded
        } else {
            encodeFailure(
                requestId = requestId,
                code = SourceHostError.Code.RESPONSE_TOO_LARGE,
                message = "Host response exceeded the allowed size.",
                retryable = false,
            )
        }
    }

    private fun encode(result: SourceHostResult): String =
        when (result) {
            is SourceHostResult.Success ->
                buildJsonObject {
                    put("type", JsonPrimitive("response"))
                    put("requestId", JsonPrimitive(result.requestId))
                    put("ok", JsonPrimitive(true))
                    // A host implementation that answers with plain text is still answerable; the
                    // WebView bridge made the same allowance.
                    val value =
                        try {
                            json.parseToJsonElement(result.resultJson)
                        } catch (_: RuntimeException) {
                            JsonPrimitive(result.resultJson)
                        }
                    put("result", value)
                }.toString()

            is SourceHostResult.Failure ->
                encodeFailure(
                    requestId = result.requestId,
                    code = result.error.code,
                    message = result.error.message,
                    retryable = result.error.retryable,
                )
        }

    private fun encodeFailure(
        requestId: String,
        code: SourceHostError.Code,
        message: String,
        retryable: Boolean,
    ): String =
        buildJsonObject {
            put("type", JsonPrimitive("response"))
            put("requestId", JsonPrimitive(requestId))
            put("ok", JsonPrimitive(false))
            put(
                "error",
                buildJsonObject {
                    put("code", JsonPrimitive(code.name))
                    put("message", JsonPrimitive(message))
                    put("retryable", JsonPrimitive(retryable))
                },
            )
        }.toString()

    private val json: Json = Json

    companion object {
        const val READY_SENTINEL = "__VENERA_HOST_READY__"
        const val DEFAULT_MAX_MESSAGE_BYTES = 1_048_576
        const val DEFAULT_MAX_LOG_CHARS = 4_096

        /**
         * `println` reaches logcat on device as `System.out` and stdout in unit tests. A logging
         * abstraction belongs to a later stage; what matters here is that the engine never reaches
         * for a platform API that unit tests cannot load.
         */
        val DEFAULT_LOG_SINK: (String, String) -> Unit = { level, message ->
            println("VeneraSource[$level] $message")
        }
    }
}
