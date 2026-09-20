package dev.veneranative.feature.reader.image

import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Pure geometry and memory accounting for page decoding: no Android framework beyond `kotlin.math`,
 * so the rules are unit testable on the JVM and so the decode budget is enforced by tests instead of
 * being observed on a device.
 */
object PageTiling {

    /** A tile is at most this many screen heights, which bounds how much is decoded at once. */
    const val TILE_SCREEN_HEIGHT_FACTOR = 1.5f

    /** How much more than the viewport a paged window decodes, so small pans do not re-decode. */
    const val WINDOW_OVERSCAN_FACTOR = 1.5f

    /** Bytes per pixel of the default `ARGB_8888` configuration. */
    const val BYTES_PER_PIXEL_ARGB_8888 = 4

    /**
     * ADR-0004: one decoded tile may never exceed this, whatever the page size and zoom are.
     * The reference viewport (1080 x 2000) is 8.2 MiB, so a decode may hold at most about three
     * viewports worth of pixels.
     */
    const val DEFAULT_DECODE_BUDGET_BYTES = 24L * 1024 * 1024

    private const val MAX_SAMPLE_SIZE = 128

    /** Screen pixels per source pixel when the page is fitted to the viewport width. */
    fun fitWidthScale(sourceWidthPx: Int, viewportWidthPx: Int): Float =
        viewportWidthPx.toFloat() / sourceWidthPx.toFloat().coerceAtLeast(1f)

    /** Screen pixels per source pixel when the page is contained in the viewport. */
    fun containScale(sourceWidthPx: Int, sourceHeightPx: Int, viewport: PageViewport): Float = minOf(
        viewport.widthPx / sourceWidthPx.toFloat().coerceAtLeast(1f),
        viewport.heightPx / sourceHeightPx.toFloat().coerceAtLeast(1f),
    )

    /**
     * The whole page as one tile: fitted to the viewport in paged reading, fitted to the viewport
     * width in continuous reading.
     */
    fun wholePageTile(
        pageWidthPx: Int,
        pageHeightPx: Int,
        viewport: PageViewport,
        zoom: Float,
        continuous: Boolean,
    ): PageTile {
        val scale = if (continuous) {
            fitWidthScale(pageWidthPx, viewport.widthPx)
        } else {
            containScale(pageWidthPx, pageHeightPx, viewport)
        } * zoom
        return PageTile(
            region = PageRegion(leftPx = 0, topPx = 0, widthPx = pageWidthPx, heightPx = pageHeightPx),
            displayWidthPx = (pageWidthPx * scale).roundToInt().coerceAtLeast(1),
            displayHeightPx = (pageHeightPx * scale).roundToInt().coerceAtLeast(1),
            fitWidthOnly = continuous,
        )
    }

    /**
     * Continuous reading: fit the page to the viewport width and cut it into tiles no taller than
     * [TILE_SCREEN_HEIGHT_FACTOR] screens, so an ultra long strip never becomes one huge bitmap.
     */
    fun continuousTiles(
        sourceWidthPx: Int,
        sourceHeightPx: Int,
        viewport: PageViewport,
        zoom: Float,
    ): List<PageTile> {
        val scale = fitWidthScale(sourceWidthPx, viewport.widthPx) * zoom
        val displayWidthPx = (sourceWidthPx * scale).roundToInt().coerceAtLeast(1)
        val maxTileSourceHeightPx = (viewport.heightPx * TILE_SCREEN_HEIGHT_FACTOR / scale)
            .roundToInt()
            .coerceIn(1, sourceHeightPx)
        val count = ceil(sourceHeightPx.toFloat() / maxTileSourceHeightPx.toFloat()).toInt()
        return List(count) { index ->
            val topPx = index * maxTileSourceHeightPx
            val heightPx = minOf(maxTileSourceHeightPx, sourceHeightPx - topPx)
            PageTile(
                region = PageRegion(leftPx = 0, topPx = topPx, widthPx = sourceWidthPx, heightPx = heightPx),
                displayWidthPx = displayWidthPx,
                displayHeightPx = (heightPx * scale).roundToInt().coerceAtLeast(1),
            )
        }
    }

    /**
     * Paged reading: contain the page in the viewport and decode only the window around the current
     * pan position. Zooming raises the scale, so the same screen area maps to fewer source pixels
     * instead of up-scaling one small bitmap.
     */
    fun windowTile(
        sourceWidthPx: Int,
        sourceHeightPx: Int,
        viewport: PageViewport,
        zoom: Float,
        offsetXPx: Float,
        offsetYPx: Float,
    ): PageTile {
        val scale = containScale(sourceWidthPx, sourceHeightPx, viewport) * zoom
        val contentWidthPx = sourceWidthPx * scale
        val contentHeightPx = sourceHeightPx * scale
        val windowWidthPx = minOf(contentWidthPx, viewport.widthPx * WINDOW_OVERSCAN_FACTOR)
        val windowHeightPx = minOf(contentHeightPx, viewport.heightPx * WINDOW_OVERSCAN_FACTOR)
        val centerXPx = contentWidthPx / 2f - offsetXPx
        val centerYPx = contentHeightPx / 2f - offsetYPx
        val leftPx = (centerXPx - windowWidthPx / 2f)
            .coerceIn(0f, (contentWidthPx - windowWidthPx).coerceAtLeast(0f))
        val topPx = (centerYPx - windowHeightPx / 2f)
            .coerceIn(0f, (contentHeightPx - windowHeightPx).coerceAtLeast(0f))

        val regionLeftPx = (leftPx / scale).toInt().coerceIn(0, sourceWidthPx - 1)
        val regionTopPx = (topPx / scale).toInt().coerceIn(0, sourceHeightPx - 1)
        val regionWidthPx = ceil(windowWidthPx / scale)
            .toInt()
            .coerceAtLeast(1)
            .coerceAtMost(sourceWidthPx - regionLeftPx)
        val regionHeightPx = ceil(windowHeightPx / scale)
            .toInt()
            .coerceAtLeast(1)
            .coerceAtMost(sourceHeightPx - regionTopPx)

        return PageTile(
            region = PageRegion(regionLeftPx, regionTopPx, regionWidthPx, regionHeightPx),
            displayWidthPx = (regionWidthPx * scale).roundToInt().coerceAtLeast(1),
            displayHeightPx = (regionHeightPx * scale).roundToInt().coerceAtLeast(1),
            contentOffsetXPx = (regionLeftPx * scale).roundToInt(),
            contentOffsetYPx = (regionTopPx * scale).roundToInt(),
        )
    }

    /**
     * Power-of-two `inSampleSize` that keeps the decoded bitmap at or above the requested size.
     *
     * This is the conservative rule an image loader uses, and it is what the sampled baseline is
     * measured with: it never blurs, but it can allocate far more than the screen needs.
     */
    fun calculateInSampleSize(
        sourceWidthPx: Int,
        sourceHeightPx: Int,
        targetWidthPx: Int,
        targetHeightPx: Int,
    ): Int {
        if (targetWidthPx <= 0 && targetHeightPx <= 0) return 1
        var sampleSize = 1
        var width = sourceWidthPx
        var height = sourceHeightPx
        while (width > 1 && height > 1) {
            val nextWidth = width / 2
            val nextHeight = height / 2
            val widthStillEnough = targetWidthPx <= 0 || nextWidth >= targetWidthPx
            val heightStillEnough = targetHeightPx <= 0 || nextHeight >= targetHeightPx
            if (!widthStillEnough || !heightStillEnough) break
            sampleSize *= 2
            width = nextWidth
            height = nextHeight
        }
        return sampleSize
    }

    /**
     * Smallest power of two whose decoded size fits [budgetBytes].
     *
     * This is where memory wins over sharpness: in the middle zoom band a conservative sample can
     * need several times the viewport, so the region path trades a bounded amount of GPU up-scaling
     * for a hard allocation ceiling.
     */
    fun budgetInSampleSize(
        regionWidthPx: Int,
        regionHeightPx: Int,
        budgetBytes: Long,
    ): Int {
        var sampleSize = 1
        while (sampleSize < MAX_SAMPLE_SIZE) {
            val decoded = decodedSize(regionWidthPx, regionHeightPx, sampleSize)
            if (decodedByteCount(decoded.widthPx, decoded.heightPx) <= budgetBytes) break
            sampleSize *= 2
        }
        return sampleSize
    }

    /** Sampling the region decoder uses: sharp enough for the display, never over the budget. */
    fun regionInSampleSize(
        regionWidthPx: Int,
        regionHeightPx: Int,
        targetWidthPx: Int,
        targetHeightPx: Int,
        budgetBytes: Long = DEFAULT_DECODE_BUDGET_BYTES,
    ): Int = maxOf(
        calculateInSampleSize(regionWidthPx, regionHeightPx, targetWidthPx, targetHeightPx),
        budgetInSampleSize(regionWidthPx, regionHeightPx, budgetBytes),
    )

    /** Pixel size after sampling. Decoders round up, so this is an upper bound, not an exact size. */
    fun decodedSize(regionWidthPx: Int, regionHeightPx: Int, sampleSize: Int): PageViewport {
        val safeSampleSize = sampleSize.coerceAtLeast(1)
        return PageViewport(
            widthPx = ceilDiv(regionWidthPx, safeSampleSize).coerceAtLeast(1),
            heightPx = ceilDiv(regionHeightPx, safeSampleSize).coerceAtLeast(1),
        )
    }

    /** Bytes a decoded bitmap occupies with [bytesPerPixel], `ARGB_8888` by default. */
    fun decodedByteCount(
        widthPx: Int,
        heightPx: Int,
        bytesPerPixel: Int = BYTES_PER_PIXEL_ARGB_8888,
    ): Long = widthPx.toLong() * heightPx.toLong() * bytesPerPixel.toLong()

    /** Bytes a whole-page tile costs when decoded with the conservative image-loader sampling. */
    fun sampledDecodeByteCount(tile: PageTile): Long {
        val targetHeightPx = if (tile.fitWidthOnly) 0 else tile.displayHeightPx
        val sampleSize = calculateInSampleSize(
            sourceWidthPx = tile.region.widthPx,
            sourceHeightPx = tile.region.heightPx,
            targetWidthPx = tile.displayWidthPx,
            targetHeightPx = targetHeightPx,
        )
        val decoded = decodedSize(tile.region.widthPx, tile.region.heightPx, sampleSize)
        return decodedByteCount(decoded.widthPx, decoded.heightPx)
    }

    /** Bytes a region tile costs with the region sampling rule, i.e. what the reader allocates. */
    fun regionDecodeByteCount(
        tile: PageTile,
        budgetBytes: Long = DEFAULT_DECODE_BUDGET_BYTES,
    ): Long {
        val sampleSize = regionInSampleSize(
            regionWidthPx = tile.region.widthPx,
            regionHeightPx = tile.region.heightPx,
            targetWidthPx = tile.displayWidthPx,
            targetHeightPx = tile.displayHeightPx,
            budgetBytes = budgetBytes,
        )
        val decoded = decodedSize(tile.region.widthPx, tile.region.heightPx, sampleSize)
        return decodedByteCount(decoded.widthPx, decoded.heightPx)
    }

    private fun ceilDiv(value: Int, divisor: Int): Int = (value + divisor - 1) / divisor
}
