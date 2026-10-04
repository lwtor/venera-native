package dev.veneranative.source.network

import dev.veneranative.core.model.SourceId
import dev.veneranative.core.network.AppHttpClientFactory
import dev.veneranative.core.network.NetworkFailure
import dev.veneranative.core.network.NetworkFailureMapper
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.Buffer

class SourceNetworkExecutor(
    private val baseClient: OkHttpClient = AppHttpClientFactory.create(),
    private val cookieJars: PerSourceCookieJarRegistry = PerSourceCookieJarRegistry(),
    private val maxConcurrentPerSource: Int = DEFAULT_MAX_CONCURRENT_PER_SOURCE,
    private val maxResponseBytes: Long = DEFAULT_MAX_RESPONSE_BYTES,
    private val callTimeoutMillis: Long = DEFAULT_CALL_TIMEOUT_MILLIS,
    private val onRequestFailure: (SourceId, String) -> Unit = { _, _ -> },
    private val diagnosticSourceIds: Set<SourceId> = emptySet(),
    private val onNetworkTrace: (SourceId, String) -> Unit = { _, _ -> },
) {
    private val clients = ConcurrentHashMap<SourceId, OkHttpClient>()
    private val sourcePermits = ConcurrentHashMap<SourceId, Semaphore>()
    private val activeCalls = mutableMapOf<SourceId, MutableSet<Call>>()
    private val epochs = mutableMapOf<SourceId, Long>()

    init {
        require(maxConcurrentPerSource > 0)
        require(maxResponseBytes > 0)
        require(callTimeoutMillis > 0)
    }

    suspend fun execute(sourceId: SourceId, request: SourceHttpRequest): SourceHttpResult {
        val epoch = synchronized(activeCalls) { epochs[sourceId] ?: 0L }
        val permits = sourcePermits.computeIfAbsent(sourceId) { Semaphore(maxConcurrentPerSource) }
        return permits.withPermit {
            val call =
                try {
                    clientFor(sourceId).newCall(request.toOkHttpRequest())
                } catch (_: IllegalArgumentException) {
                    return SourceHttpResult.Failure(SourceNetworkError.InvalidRequest)
                }
            val registered = synchronized(activeCalls) {
                if ((epochs[sourceId] ?: 0L) != epoch) false
                else {
                    activeCalls.getOrPut(sourceId) { mutableSetOf() }.add(call)
                    true
                }
            }
            if (!registered) {
                call.cancel()
                return SourceHttpResult.Failure(SourceNetworkError.Cancelled)
            }
            val result = try {
                executeCall(call)
            } finally {
                synchronized(activeCalls) {
                    activeCalls[sourceId]?.remove(call)
                    if (activeCalls[sourceId]?.isEmpty() == true) activeCalls.remove(sourceId)
                }
            }
            if (result is SourceHttpResult.Failure && result.error !is SourceNetworkError.Cancelled) {
                runCatching {
                    onRequestFailure(sourceId, result.error.javaClass.simpleName.ifBlank { "SourceNetworkError" })
                }
            }
            result
        }
    }

    fun clearSource(sourceId: SourceId) {
        val calls = synchronized(activeCalls) {
            epochs[sourceId] = (epochs[sourceId] ?: 0L) + 1L
            clients.remove(sourceId)
            cookieJars.clear(sourceId)
            sourcePermits.remove(sourceId)
            activeCalls.remove(sourceId)?.toList().orEmpty()
        }
        calls.forEach(Call::cancel)
    }

    private fun clientFor(sourceId: SourceId): OkHttpClient =
        clients.computeIfAbsent(sourceId) {
            baseClient.newBuilder()
                .cookieJar(cookieJars.forSource(sourceId))
                .callTimeout(callTimeoutMillis, TimeUnit.MILLISECONDS)
                .apply {
                    if (sourceId in diagnosticSourceIds) {
                        eventListenerFactory { call ->
                            SourceNetworkTraceListener(
                                sourceId = sourceId,
                                method = call.request().method,
                                host = call.request().url.host,
                                emit = { message -> onNetworkTrace(sourceId, message) },
                            )
                        }
                    }
                }
                .build()
        }

    private suspend fun executeCall(call: Call): SourceHttpResult =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (!continuation.isActive) return
                        val error =
                            when (NetworkFailureMapper.from(e, call.isCanceled())) {
                                NetworkFailure.Cancelled -> SourceNetworkError.Cancelled
                                NetworkFailure.Timeout -> SourceNetworkError.Timeout
                                NetworkFailure.Protocol,
                                NetworkFailure.Connection -> SourceNetworkError.Connection(e.safeDiagnostic())
                            }
                        continuation.resume(SourceHttpResult.Failure(error))
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val result = try {
                            response.use {
                                val body = response.body
                                if (body.contentLength() > maxResponseBytes) {
                                    SourceHttpResult.Failure(
                                        SourceNetworkError.ResponseTooLarge(maxResponseBytes),
                                    )
                                } else {
                                    val buffer = Buffer()
                                    val source = body.source()
                                    while (buffer.size <= maxResponseBytes) {
                                        val read = source.read(buffer, minOf(8192L, maxResponseBytes - buffer.size + 1))
                                        if (read == -1L) break
                                    }
                                    val bytes = buffer.readByteArray()
                                    if (bytes.size > maxResponseBytes) {
                                        SourceHttpResult.Failure(
                                            SourceNetworkError.ResponseTooLarge(maxResponseBytes),
                                        )
                                    } else {
                                        SourceHttpResult.Success(
                                            SourceHttpResponse(
                                                statusCode = response.code,
                                                headers = response.headers.toMultimap(),
                                                body = bytes.toString(Charsets.UTF_8),
                                            ),
                                        )
                                    }
                                }
                            }
                        } catch (failure: IOException) {
                            onFailure(call, failure)
                            return
                        }
                        if (continuation.isActive) continuation.resume(result)
                    }
                },
            )
        }

    private fun SourceHttpRequest.toOkHttpRequest(): Request {
        val builder = Request.Builder().url(url)
        // Let OkHttp negotiate and transparently decode response compression. Source scripts may
        // advertise encodings the app transport cannot decode (JM advertises gzip, deflate, br and
        // zstd); forwarding that header disables OkHttp's transparent gzip handling and can expose
        // compressed bytes to the JavaScript JSON parser.
        headers.forEach { (name, value) ->
            if (!name.equals("Accept-Encoding", ignoreCase = true)) {
                builder.header(name, value)
            }
        }
        return when (method) {
            SourceHttpRequest.Method.GET -> {
                require(body == null) { "GET requests cannot have a body." }
                builder.get().build()
            }
            SourceHttpRequest.Method.POST -> {
                val mediaType = headers.entries
                    .firstOrNull { (name, _) -> name.equals("Content-Type", ignoreCase = true) }
                    ?.value
                    ?.toMediaTypeOrNull()
                builder.post(ExactContentTypeRequestBody((body ?: "").toByteArray(Charsets.UTF_8), mediaType)).build()
            }
        }
    }

    /** Keeps a source script's Content-Type verbatim; OkHttp's convenience body factory adds a charset. */
    private class ExactContentTypeRequestBody(
        private val bytes: ByteArray,
        private val mediaType: okhttp3.MediaType?,
    ) : RequestBody() {
        override fun contentType() = mediaType

        override fun contentLength() = bytes.size.toLong()

        override fun writeTo(sink: BufferedSink) {
            sink.write(bytes)
        }
    }

    private fun IOException.safeDiagnostic(): String {
        var root: Throwable = this
        repeat(4) {
            root.cause?.let { root = it } ?: return@repeat
        }
        val kind = root.javaClass.simpleName.ifBlank { "IOException" }
        val message = root.message.orEmpty()
            .replace(Regex("https?://\\S+"), "<url>")
            .replace(Regex("([?&][^=\\s]+)=([^&\\s]+)"), "$1=<redacted>")
            .filterNot(Char::isISOControl)
            .take(140)
        return if (message.isBlank()) kind else "$kind: $message"
    }

    private companion object {
        const val DEFAULT_MAX_CONCURRENT_PER_SOURCE = 4
        const val DEFAULT_MAX_RESPONSE_BYTES = 1_048_576L
        const val DEFAULT_CALL_TIMEOUT_MILLIS = 15_000L
    }
}
