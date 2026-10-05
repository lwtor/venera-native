package dev.veneranative.core.image.decode

import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.SystemClock
import dev.veneranative.core.image.tiling.DecodeStrategy
import dev.veneranative.core.image.tiling.DecodedPageImage
import dev.veneranative.core.image.tiling.PageDecodeRequest
import dev.veneranative.core.image.tiling.PageTile
import dev.veneranative.core.image.tiling.PageTiling
import dev.veneranative.core.image.tiling.PageViewport
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
    private val openDecoders = linkedMapOf<String, DecoderEntry>()
    private var closed = false

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
        val decoded = withDecoder(request.path) { decoder ->
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
            bitmap to inSampleSize
        }
        DecodedPageImage(
            bitmap = decoded.first,
            byteCount = decoded.first.allocationByteCount,
            decodeMillis = clock() - startedAtMillis,
            inSampleSize = decoded.second,
            strategy = strategy,
        )
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            val iterator = openDecoders.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next().value
                if (entry.activeDecodes == 0) {
                    iterator.remove()
                    entry.decoder.recycle()
                }
            }
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

    private class DecoderEntry(val decoder: BitmapRegionDecoder, var activeDecodes: Int = 0)

    /** Keep a decoder leased until its native decode finishes so LRU eviction cannot recycle it. */
    private inline fun <T> withDecoder(path: String, block: (BitmapRegionDecoder) -> T): T {
        val entry = synchronized(lock) {
            check(!closed) { "Region decoder is closed" }
            val existing = openDecoders.remove(path)
            val selected = existing ?: DecoderEntry(createDecoder(path))
            selected.activeDecodes++
            openDecoders[path] = selected
            trimLocked()
            selected
        }
        try {
            return block(entry.decoder)
        } finally {
            synchronized(lock) {
                entry.activeDecodes--
                if (closed && entry.activeDecodes == 0) {
                    openDecoders.entries.removeAll { it.value === entry }
                    entry.decoder.recycle()
                } else {
                    trimLocked()
                }
            }
        }
    }

    private fun trimLocked() {
        while (openDecoders.size > maxOpenDecoders) {
            val oldestIdle = openDecoders.entries.firstOrNull { it.value.activeDecodes == 0 } ?: return
            openDecoders.remove(oldestIdle.key)?.decoder?.recycle()
        }
    }

    // Path-based creation is deprecated in favour of descriptor-based creation; the descriptor form
    // is decided when the pipeline moves to :core:image, together with the source abstraction.
    @Suppress("DEPRECATION")
    private fun createDecoder(path: String): BitmapRegionDecoder =
        BitmapRegionDecoder.newInstance(path, false)

    private companion object {
        const val DEFAULT_MAX_OPEN_DECODERS = 3
        const val MAX_WHOLE_PAGE_SAMPLE_SIZE = 2
    }
}
