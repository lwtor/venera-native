package dev.veneranative.feature.reader.image

import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.SystemClock
import dev.veneranative.core.model.ComicPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Region decoder: decode only the part of the page that is actually displayed.
 *
 * Continuous reading cuts an ultra long strip into screen-sized tiles; paged reading decodes a
 * window around the current pan position. Both keep the decoded size proportional to the screen
 * instead of to the source file, and [budgetBytes] is a hard allocation ceiling per decode.
 *
 * A page that can be decoded whole within the budget is decoded whole: tiling a page that already
 * fits only adds seams and re-decodes without saving memory.
 */
class RegionPageImageDecoder(
    private val budgetBytes: Long = PageTiling.DEFAULT_DECODE_BUDGET_BYTES,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    private val maxOpenDecoders: Int = DEFAULT_MAX_OPEN_DECODERS,
) : PageImageDecoder {

    private val lock = Any()
    private val openDecoders = linkedMapOf<String, BitmapRegionDecoder>()

    override val strategy: DecodeStrategy = DecodeStrategy.Region

    override fun plan(
        page: ComicPage,
        viewport: PageViewport,
        zoom: Float,
        continuous: Boolean,
    ): List<PageTile> {
        val wholePageTile = PageTiling.wholePageTile(
            pageWidthPx = page.widthPx,
            pageHeightPx = page.heightPx,
            viewport = viewport,
            zoom = zoom,
            continuous = continuous,
        )
        if (canDecodeWholePage(wholePageTile)) return listOf(wholePageTile)
        return if (continuous) {
            PageTiling.continuousTiles(
                sourceWidthPx = page.widthPx,
                sourceHeightPx = page.heightPx,
                viewport = viewport,
                zoom = zoom,
            )
        } else {
            listOf(
                PageTiling.windowTile(
                    sourceWidthPx = page.widthPx,
                    sourceHeightPx = page.heightPx,
                    viewport = viewport,
                    zoom = zoom,
                    offsetXPx = 0f,
                    offsetYPx = 0f,
                ),
            )
        }
    }

    /** Paged reading re-plans for the current pan offset; continuous reading uses sequential tiles. */
    override fun planWindow(
        page: ComicPage,
        viewport: PageViewport,
        zoom: Float,
        offsetXPx: Float,
        offsetYPx: Float,
    ): PageTile = PageTiling.windowTile(
        sourceWidthPx = page.widthPx,
        sourceHeightPx = page.heightPx,
        viewport = viewport,
        zoom = zoom,
        offsetXPx = offsetXPx,
        offsetYPx = offsetYPx,
    )

    override suspend fun decode(request: PageDecodeRequest): DecodedPageImage = withContext(Dispatchers.IO) {
        val startedAtMillis = clock()
        val region = requireNotNull(request.region) { "Region decoding requires a region" }
        val decoder = decoderFor(request.path)
        val rect = Rect(
            region.leftPx,
            region.topPx,
            (region.leftPx + region.widthPx).coerceAtMost(decoder.width),
            (region.topPx + region.heightPx).coerceAtMost(decoder.height),
        )
        val inSampleSize = PageTiling.regionInSampleSize(
            regionWidthPx = rect.width(),
            regionHeightPx = rect.height(),
            targetWidthPx = request.targetWidthPx,
            targetHeightPx = request.targetHeightPx,
            budgetBytes = budgetBytes,
        )
        val options = BitmapFactory.Options().apply { this.inSampleSize = inSampleSize }
        val bitmap = decoder.decodeRegion(rect, options)
            ?: throw IllegalStateException("Page region is not decodable")
        DecodedPageImage(
            bitmap = bitmap,
            byteCount = bitmap.allocationByteCount,
            decodeMillis = clock() - startedAtMillis,
            inSampleSize = inSampleSize,
            strategy = strategy,
        )
    }

    override fun close() {
        synchronized(lock) {
            openDecoders.values.forEach { it.recycle() }
            openDecoders.clear()
        }
    }

    /**
     * True when decoding the whole page stays inside the budget and does not need a heavy
     * down-scale. The sample check is what stops a strip contained in a phone viewport from being
     * "decoded whole" at an unreadable resolution.
     */
    private fun canDecodeWholePage(tile: PageTile): Boolean {
        val targetHeightPx = if (tile.fitWidthOnly) 0 else tile.displayHeightPx
        val sampleSize = PageTiling.calculateInSampleSize(
            sourceWidthPx = tile.region.widthPx,
            sourceHeightPx = tile.region.heightPx,
            targetWidthPx = tile.displayWidthPx,
            targetHeightPx = targetHeightPx,
        )
        if (sampleSize > MAX_WHOLE_PAGE_SAMPLE_SIZE) return false
        val decoded = PageTiling.decodedSize(tile.region.widthPx, tile.region.heightPx, sampleSize)
        return PageTiling.decodedByteCount(decoded.widthPx, decoded.heightPx) <= budgetBytes
    }

    // Path-based creation is deprecated in favour of descriptor-based creation; the descriptor form
    // is decided when the pipeline moves to :core:image, together with the source abstraction.
    @Suppress("DEPRECATION")
    private fun decoderFor(path: String): BitmapRegionDecoder = synchronized(lock) {
        val existing = openDecoders.remove(path)
        if (existing != null) {
            openDecoders[path] = existing
            return@synchronized existing
        }
        while (openDecoders.size >= maxOpenDecoders) {
            openDecoders.keys.firstOrNull()?.let { oldest -> openDecoders.remove(oldest)?.recycle() }
                ?: break
        }
        BitmapRegionDecoder.newInstance(path, false).also { openDecoders[path] = it }
    }

    private companion object {
        const val DEFAULT_MAX_OPEN_DECODERS = 3
        const val MAX_WHOLE_PAGE_SAMPLE_SIZE = 2
    }
}
