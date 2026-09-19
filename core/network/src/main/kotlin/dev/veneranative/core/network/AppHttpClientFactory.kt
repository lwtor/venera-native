package dev.veneranative.core.network

import java.util.concurrent.TimeUnit
import okhttp3.Dispatcher
import okhttp3.OkHttpClient

object AppHttpClientFactory {
    fun createDispatcher(
        maxRequests: Int = DEFAULT_MAX_REQUESTS,
        maxRequestsPerHost: Int = DEFAULT_MAX_REQUESTS_PER_HOST,
    ): Dispatcher {
        require(maxRequests > 0)
        require(maxRequestsPerHost > 0)
        return Dispatcher().apply {
            this.maxRequests = maxRequests
            this.maxRequestsPerHost = maxRequestsPerHost
        }
    }

    fun create(
        dispatcher: Dispatcher = createDispatcher(),
        connectTimeoutMillis: Long = DEFAULT_CONNECT_TIMEOUT_MILLIS,
        readTimeoutMillis: Long = DEFAULT_READ_TIMEOUT_MILLIS,
        writeTimeoutMillis: Long = DEFAULT_WRITE_TIMEOUT_MILLIS,
    ): OkHttpClient {
        require(connectTimeoutMillis > 0)
        require(readTimeoutMillis > 0)
        require(writeTimeoutMillis > 0)
        return OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectTimeout(connectTimeoutMillis, TimeUnit.MILLISECONDS)
            .readTimeout(readTimeoutMillis, TimeUnit.MILLISECONDS)
            .writeTimeout(writeTimeoutMillis, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private const val DEFAULT_MAX_REQUESTS = 32
    private const val DEFAULT_MAX_REQUESTS_PER_HOST = 8
    private const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 15_000L
    private const val DEFAULT_READ_TIMEOUT_MILLIS = 30_000L
    private const val DEFAULT_WRITE_TIMEOUT_MILLIS = 30_000L
}
