package dev.veneranative.source.network

import dev.veneranative.core.model.SourceId
import dev.veneranative.core.network.AppHttpClientFactory
import dev.veneranative.core.network.NetworkFailure
import dev.veneranative.core.network.NetworkFailureMapper
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer

class SourceNetworkExecutor(
    private val baseClient: OkHttpClient = AppHttpClientFactory.create(),
    private val cookieJars: PerSourceCookieJarRegistry = PerSourceCookieJarRegistry(),
    private val maxConcurrentPerSource: Int = DEFAULT_MAX_CONCURRENT_PER_SOURCE,
    private val maxResponseBytes: Long = DEFAULT_MAX_RESPONSE_BYTES,
) {
    private val clients = ConcurrentHashMap<SourceId, OkHttpClient>()
    private val activeCounts = ConcurrentHashMap<SourceId, AtomicInteger>()

    init {
        require(maxConcurrentPerSource > 0)
        require(maxResponseBytes > 0)
    }

    suspend fun execute(sourceId: SourceId, request: SourceHttpRequest): SourceHttpResult {
        val counter = activeCounts.computeIfAbsent(sourceId) { AtomicInteger() }
        if (!tryAcquire(counter)) {
            return SourceHttpResult.Failure(SourceNetworkError.ConcurrencyLimit)
        }

        return try {
            val call =
                try {
                    clientFor(sourceId).newCall(request.toOkHttpRequest())
                } catch (_: IllegalArgumentException) {
                    return SourceHttpResult.Failure(SourceNetworkError.InvalidRequest)
                }
            executeCall(call)
        } finally {
            counter.decrementAndGet()
        }
    }

    fun clearSource(sourceId: SourceId) {
        clients.remove(sourceId)
        cookieJars.clear(sourceId)
        activeCounts.remove(sourceId)
    }

    private fun clientFor(sourceId: SourceId): OkHttpClient =
        clients.computeIfAbsent(sourceId) {
            baseClient.newBuilder()
                .cookieJar(cookieJars.forSource(sourceId))
                .build()
        }

    private fun tryAcquire(counter: AtomicInteger): Boolean {
        while (true) {
            val current = counter.get()
            if (current >= maxConcurrentPerSource) return false
            if (counter.compareAndSet(current, current + 1)) return true
        }
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
                                NetworkFailure.Connection,
                                -> SourceNetworkError.Connection
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
        headers.forEach { (name, value) -> builder.header(name, value) }
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
                builder.post((body ?: "").toRequestBody(mediaType)).build()
            }
        }
    }

    private companion object {
        const val DEFAULT_MAX_CONCURRENT_PER_SOURCE = 4
        const val DEFAULT_MAX_RESPONSE_BYTES = 1_048_576L
    }
}
