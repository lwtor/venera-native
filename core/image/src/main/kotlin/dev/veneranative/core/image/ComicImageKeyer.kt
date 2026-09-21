package dev.veneranative.core.image

import coil3.key.Keyer
import coil3.request.Options

/**
 * Turns a [ComicImageRequest] into its memory-cache key.
 *
 * Coil only caches results of a custom data type when a [Keyer] is registered for it, and the key
 * has to answer exactly one question: could these two requests return different bytes?
 */
class ComicImageKeyer(
    private val auth: ComicImageAuthProvider,
) : Keyer<ComicImageRequest> {

    override fun key(data: ComicImageRequest, options: Options): String? =
        ComicImageCacheKey.of(data, auth.headersFor(data.sourceId, data.url))
}
