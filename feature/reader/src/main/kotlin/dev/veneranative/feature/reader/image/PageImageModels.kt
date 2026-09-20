package dev.veneranative.feature.reader.image

import android.graphics.Bitmap

/**
 * How a page image is turned into pixels.
 *
 * The two strategies exist so S0-06 can compare them with measurements instead of assumptions.
 */
enum class DecodeStrategy {
    /**
     * Decode the whole page and let the decoder down-sample it.
     *
     * This is behaviourally equivalent to a generic image loader that fits the image to a target
     * size, so it is the baseline the S0-06 decision is measured against.
     */
    Sampled,

    /** Decode only the visible region or tile, at the scale that is actually displayed. */
    Region,
}

/** The area the reader can use to display a page, in pixels. */
data class PageViewport(
    val widthPx: Int,
    val heightPx: Int,
)

/** A rectangle of the source image, in source pixels. */
data class PageRegion(
    val leftPx: Int,
    val topPx: Int,
    val widthPx: Int,
    val heightPx: Int,
)

/**
 * One decode unit of a page.
 *
 * A page may be a single tile or many tiles; the decoder decides, the reader never computes
 * geometry itself. [contentOffsetXPx] / [contentOffsetYPx] place the tile inside the page for
 * paged reading; continuous reading lays tiles out sequentially and ignores them.
 */
data class PageTile(
    val region: PageRegion,
    val displayWidthPx: Int,
    val displayHeightPx: Int,
    val contentOffsetXPx: Int = 0,
    val contentOffsetYPx: Int = 0,
    /** Sample by width only and keep the full height, i.e. what a width-fitted decode produces. */
    val fitWidthOnly: Boolean = false,
)

/** A single decode request. Every request carries a target size: no request may decode at full size. */
data class PageDecodeRequest(
    val path: String,
    val targetWidthPx: Int,
    val targetHeightPx: Int,
    /** Null means "the whole page", which only the sampled strategy uses. */
    val region: PageRegion? = null,
    val fitWidthOnly: Boolean = false,
)

/** Result of one decode. Holds the bitmap so the probe can account for its bytes. */
data class DecodedPageImage(
    val bitmap: Bitmap,
    val byteCount: Int,
    val decodeMillis: Long,
    val inSampleSize: Int,
    val strategy: DecodeStrategy,
) {
    val widthPx: Int get() = bitmap.width
    val heightPx: Int get() = bitmap.height
}
