package dev.veneranative.data.source

import java.net.URI
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import kotlin.coroutines.resume

data class SourceCatalogEntry(
    val name: String,
    val key: String?,
    val version: String?,
    val description: String?,
    val scriptUrl: String,
)

sealed interface SourceCatalogResult {
    data class Success(val entries: List<SourceCatalogEntry>) : SourceCatalogResult
    data class Failure(val reason: SourceCatalogFailure) : SourceCatalogResult
}

enum class SourceCatalogFailure { InvalidLocation, Network, InvalidCatalog }

interface SourceCatalogRepository {
    suspend fun load(location: String): SourceCatalogResult
}

/** Loads Venera's JSON source index and resolves each script path against the index URL. */
class HttpSourceCatalogRepository(
    private val client: OkHttpClient,
    private val maxCatalogBytes: Long = DEFAULT_MAX_CATALOG_BYTES,
) : SourceCatalogRepository {
    init { require(maxCatalogBytes > 0) }

    override suspend fun load(location: String): SourceCatalogResult {
        val catalogUri = httpsUri(location) ?: return SourceCatalogResult.Failure(SourceCatalogFailure.InvalidLocation)
        val body = when (val response = HttpTextRequest(client, maxCatalogBytes).get(catalogUri.toString())) {
            is HttpTextResult.Failure -> return SourceCatalogResult.Failure(SourceCatalogFailure.Network)
            is HttpTextResult.Success -> response.text
        }
        return runCatching { SourceCatalogParser.parse(body, catalogUri) }
            .getOrNull()
            ?.let(SourceCatalogResult::Success)
            ?: SourceCatalogResult.Failure(SourceCatalogFailure.InvalidCatalog)
    }

    private fun httpsUri(value: String): URI? = runCatching { URI(value.trim()) }
        .getOrNull()
        ?.takeIf { it.scheme.equals("https", ignoreCase = true) && !it.host.isNullOrBlank() }

    private companion object {
        const val DEFAULT_MAX_CATALOG_BYTES = 1L * 1024 * 1024
    }
}

internal object SourceCatalogParser {
    fun parse(text: String, catalogUri: URI): List<SourceCatalogEntry> {
        require(catalogUri.scheme.equals("https", ignoreCase = true) && !catalogUri.host.isNullOrBlank())
        val array = Json.parseToJsonElement(text) as? JsonArray ?: error("Expected source array")
        return array.mapNotNull { element -> element.parseEntry(catalogUri) }
            .distinctBy { it.scriptUrl }
            .also { if (it.isEmpty()) error("No usable sources") }
    }

    private fun JsonElement.parseEntry(catalogUri: URI): SourceCatalogEntry? {
        val item = this as? JsonObject ?: return null
        val name = item.string("name")?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val scriptLocation = item.string("url") ?: item.string("fileName")
            ?: item.string("filename") ?: return null
        val scriptUri = runCatching { catalogUri.resolve(scriptLocation).normalize() }.getOrNull()
            ?.takeIf { it.scheme.equals("https", ignoreCase = true) } ?: return null
        return SourceCatalogEntry(
            name = name,
            key = item.string("key")?.trim()?.takeIf(String::isNotEmpty),
            version = item.string("version")?.trim()?.takeIf(String::isNotEmpty),
            description = item.string("description")?.trim()?.takeIf(String::isNotEmpty),
            scriptUrl = scriptUri.toString(),
        )
    }

    private fun JsonObject.string(name: String): String? =
        this[name]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }

}

internal sealed interface HttpTextResult {
    data class Success(val text: String) : HttpTextResult
    data object Failure : HttpTextResult
}

/** A bounded, cancellable text GET shared by source catalogs and source scripts. */
internal class HttpTextRequest(
    private val client: OkHttpClient,
    private val maxBytes: Long,
) {
    suspend fun get(url: String): HttpTextResult {
        val call = runCatching {
            client.newCall(Request.Builder().url(url).get().build())
        }.getOrElse { return HttpTextResult.Failure }
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: java.io.IOException) {
                    if (continuation.isActive) continuation.resume(HttpTextResult.Failure)
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching {
                        response.use {
                            val body = it.body
                            if (!it.isSuccessful || !it.request.url.isHttps || body.contentLength() > maxBytes) return@use HttpTextResult.Failure
                            val source = body.source()
                            val buffer = Buffer()
                            var total = 0L
                            while (true) {
                                val read = source.read(buffer, minOf(8192L, maxBytes - total + 1))
                                if (read == -1L) break
                                total += read
                                if (total > maxBytes) return@use HttpTextResult.Failure
                            }
                            HttpTextResult.Success(buffer.readUtf8())
                        }
                    }.getOrDefault(HttpTextResult.Failure)
                    if (continuation.isActive) continuation.resume(result)
                }
            })
        }
    }
}

private val VENERA_SOURCE_CATALOG = "https://cdn.jsdelivr.net/gh/venera-app/venera-configs@main/index.json"

fun defaultVeneraSourceCatalogUrl(): String = VENERA_SOURCE_CATALOG
