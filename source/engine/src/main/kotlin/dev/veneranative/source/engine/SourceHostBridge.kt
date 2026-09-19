package dev.veneranative.source.engine

import androidx.javascriptengine.JavaScriptIsolate
import androidx.javascriptengine.JavaScriptSandbox
import androidx.javascriptengine.Message
import androidx.javascriptengine.MessagePort
import dev.veneranative.core.model.SourceId
import dev.veneranative.source.api.SourceHostApi
import dev.veneranative.source.api.SourceHostError
import dev.veneranative.source.api.SourceHostRequest
import dev.veneranative.source.api.SourceHostResult
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import kotlin.text.Charsets.UTF_8
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal class SourceHostBridge(
    private val sourceId: SourceId,
    sandbox: JavaScriptSandbox,
    private val isolate: JavaScriptIsolate,
    private val hostApi: SourceHostApi,
    private val json: Json = Json,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requests = ConcurrentHashMap<String, Job>()
    private val callbackExecutor = Executor(Runnable::run)
    private val port: MessagePort =
        if (sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_MESSAGE_PORTS)) {
            isolate.createMessageChannel(PORT_NAME, callbackExecutor) { message ->
                onMessage(message)
            }
        } else {
            throw IllegalStateException("MessagePort feature is unavailable.")
        }

    suspend fun initialize() {
        val result = isolate.evaluateJavaScriptAsync(BOOTSTRAP_SCRIPT).awaitCancellable()
        check(result == READY_SENTINEL) { "Host bridge did not initialize." }
    }

    override fun close() {
        requests.values.forEach(Job::cancel)
        requests.clear()
        scope.cancel()
        port.close()
    }

    private fun onMessage(message: Message) {
        if (message.type != Message.TYPE_STRING) return
        val text = message.string
        if (text.toByteArray(UTF_8).size > MAX_MESSAGE_BYTES) return

        val request =
            try {
                decodeRequest(text)
            } catch (_: RuntimeException) {
                return
            }

        if (!hostApi.isMethodAllowed(request.method)) {
            postResult(
                SourceHostResult.Failure(
                    requestId = request.requestId,
                    error =
                        SourceHostError(
                            code = SourceHostError.Code.METHOD_NOT_ALLOWED,
                            message = "Host method is not allowed.",
                            retryable = false,
                        ),
                ),
            )
            return
        }

        val job =
            scope.launch(start = CoroutineStart.LAZY) {
                val result =
                    try {
                        hostApi.invoke(request)
                    } catch (_: kotlinx.coroutines.CancellationException) {
                        SourceHostResult.Failure(
                            requestId = request.requestId,
                            error =
                                SourceHostError(
                                    SourceHostError.Code.CANCELLED,
                                    "Host request was cancelled.",
                                    retryable = true,
                                ),
                        )
                    } catch (_: Throwable) {
                        SourceHostResult.Failure(
                            requestId = request.requestId,
                            error =
                                SourceHostError(
                                    SourceHostError.Code.INTERNAL,
                                    "Host request failed.",
                                    retryable = true,
                                ),
                        )
                    }
                postResult(result)
            }
        requests.put(request.requestId, job)?.cancel()
        job.invokeOnCompletion { requests.remove(request.requestId, job) }
        job.start()
    }

    private fun decodeRequest(text: String): SourceHostRequest {
        val root = json.parseToJsonElement(text).jsonObject
        require(root.requiredString("type") == "request")
        val requestId = root.requiredString("requestId")
        val invocationId = root.requiredString("invocationId")
        val method = root.requiredString("method")
        val payload = root["payload"] ?: JsonObject(emptyMap())
        return SourceHostRequest(
            sourceId = sourceId,
            invocationId = invocationId,
            requestId = requestId,
            method = method,
            payloadJson = payload.toString(),
        )
    }

    private fun postResult(result: SourceHostResult) {
        val response =
            when (result) {
                is SourceHostResult.Success ->
                    buildJsonObject {
                        put("type", JsonPrimitive("response"))
                        put("requestId", JsonPrimitive(result.requestId))
                        put("ok", JsonPrimitive(true))
                        val value =
                            try {
                                json.parseToJsonElement(result.resultJson)
                            } catch (_: RuntimeException) {
                                JsonPrimitive(result.resultJson)
                            }
                        put("result", value)
                    }
                is SourceHostResult.Failure ->
                    buildJsonObject {
                        put("type", JsonPrimitive("response"))
                        put("requestId", JsonPrimitive(result.requestId))
                        put("ok", JsonPrimitive(false))
                        put(
                            "error",
                            buildJsonObject {
                                put("code", JsonPrimitive(result.error.code.name))
                                put("message", JsonPrimitive(result.error.message))
                                put("retryable", JsonPrimitive(result.error.retryable))
                            },
                        )
                    }
            }
        val encoded = response.toString()
        val safeResponse =
            if (encoded.toByteArray(UTF_8).size <= MAX_MESSAGE_BYTES) {
                encoded
            } else {
                buildJsonObject {
                    put("type", JsonPrimitive("response"))
                    put("requestId", JsonPrimitive(result.requestId))
                    put("ok", JsonPrimitive(false))
                    put(
                        "error",
                        buildJsonObject {
                            put("code", JsonPrimitive(SourceHostError.Code.RESPONSE_TOO_LARGE.name))
                            put("message", JsonPrimitive("Host response exceeded the allowed size."))
                            put("retryable", JsonPrimitive(false))
                        },
                    )
                }.toString()
            }
        port.postMessage(Message.createStringMessage(safeResponse))
    }

    private fun JsonObject.requiredString(name: String): String =
        get(name)?.jsonPrimitive?.content?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("Missing protocol field.")

    companion object {
        const val PORT_NAME = "veneraHost"
        const val READY_SENTINEL = "__VENERA_HOST_READY__"
        const val MAX_MESSAGE_BYTES = 1_048_576
        const val MAX_REQUEST_CHARS = 262_144

        val BOOTSTRAP_SCRIPT =
            """
            (async function() {
              const port = await android.getNamedPort("$PORT_NAME");
              const pending = new Map();
              let sequence = 0;
              port.onmessage = function(event) {
                let response;
                try {
                  response = JSON.parse(event.data);
                } catch (_) {
                  return;
                }
                if (response.type !== "response" || typeof response.requestId !== "string") return;
                const callbacks = pending.get(response.requestId);
                if (!callbacks) return;
                pending.delete(response.requestId);
                if (response.ok) {
                  callbacks.resolve(response.result);
                } else {
                  const error = new Error(response.error && response.error.message || "Host request failed.");
                  error.code = response.error && response.error.code || "INTERNAL";
                  error.retryable = Boolean(response.error && response.error.retryable);
                  callbacks.reject(error);
                }
              };
              globalThis.veneraHost = Object.freeze({
                call: function(method, payload) {
                  const invocationId = globalThis.__veneraInvocationId;
                  if (typeof invocationId !== "string" || invocationId.length === 0) {
                    return Promise.reject(new Error("Host call requires an active invocation."));
                  }
                  const requestId = invocationId + ":" + (++sequence);
                  return new Promise(function(resolve, reject) {
                    pending.set(requestId, { resolve: resolve, reject: reject });
                    const request = JSON.stringify({
                      type: "request",
                      requestId: requestId,
                      invocationId: invocationId,
                      method: method,
                      payload: payload == null ? {} : payload
                    });
                    if (request.length > $MAX_REQUEST_CHARS) {
                      pending.delete(requestId);
                      const error = new Error("Host request exceeded the allowed size.");
                      error.code = "INVALID_REQUEST";
                      reject(error);
                      return;
                    }
                    port.postMessage(request);
                  });
                }
              });
              return "$READY_SENTINEL";
            })()
            """.trimIndent()
    }
}
