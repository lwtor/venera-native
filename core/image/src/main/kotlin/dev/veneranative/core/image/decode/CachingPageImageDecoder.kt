package dev.veneranative.feature.reader.image

/**
 * Caches decode results so scrolling back and forth does not re-decode, and so the S0-06 probe can
 * measure how much a prefetch window actually holds.
 *
 * A cache hit reports `decodeMillis = 0` and `inSampleSize = 1`, which is how the probe separates
 * cached draws from real decodes.
 */
class CachingPageImageDecoder(
    private val delegate: PageImageDecoder,
    private val cache: PageImageCache,
) : PageImageDecoder by delegate {

    override suspend fun decode(request: PageDecodeRequest): DecodedPageImage {
        val key = cacheKey(request)
        val cached = cache.get(key)
        if (cached != null) {
            return DecodedPageImage(
                bitmap = cached,
                byteCount = cached.allocationByteCount,
                decodeMillis = 0L,
                inSampleSize = 1,
                strategy = strategy,
            )
        }
        return delegate.decode(request).also { cache.put(key, it.bitmap) }
    }

    private fun cacheKey(request: PageDecodeRequest): String = buildString {
        append(request.path)
        append('#')
        append(request.targetWidthPx)
        append('x')
        append(request.targetHeightPx)
        append('#')
        request.region?.let { region ->
            append(region.leftPx).append(',').append(region.topPx)
            append(',').append(region.widthPx).append('x').append(region.heightPx)
        } ?: append("full")
        if (request.fitWidthOnly) append("#widthOnly")
    }
}
