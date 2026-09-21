package dev.veneranative.app

import dev.veneranative.core.image.ComicImageAuthProvider
import dev.veneranative.core.model.SourceId
import dev.veneranative.source.network.PerSourceCookieJarRegistry
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Bridges the source network's per-source cookies into the image pipeline.
 *
 * Images are fetched by `:core:image`, which must not depend on `:source:network`, so this adapter
 * lives here in the assembly layer and hands over nothing but headers.
 *
 * The registry instance is the **same one** the source's own requests use, which is the point: a
 * source authenticates while loading its chapter list, and those cookies are exactly what its image
 * host later demands. A separate jar would look correct and silently 403 every page.
 *
 * Nothing is scraped into the cache key that is not also sent: [higher] feeds both sides from this
 * one call, so a key can never be computed from headers the request did not carry.
 */
class SourceCookieImageAuth(
    private val cookieJars: PerSourceCookieJarRegistry,
) : ComicImageAuthProvider {

    override fun headersFor(sourceId: SourceId?, url: String): Map<String, String> {
        val target = sourceId ?: return emptyMap()
        val httpUrl = url.toHttpUrlOrNull() ?: return emptyMap()
        val cookies = cookieJars.forSource(target).loadForRequest(httpUrl)
        if (cookies.isEmpty()) return emptyMap()
        val header = cookies.joinToString(separator = "; ") { cookie -> "${cookie.name}=${cookie.value}" }
        return mapOf("Cookie" to header)
    }
}
