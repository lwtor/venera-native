package dev.veneranative.core.model

/**
 * One half of a double-page source image, described by its horizontal span in source pixels.
 *
 * A magazine scan often stores two facing pages as one wide image (width > height). Splitting it
 * into a left and a right half makes each readable at a normal font size. The two halves overlap by
 * design so the gutter between pages is never cut away.
 */
data class PageHalf(
    /** Source x offset where this half starts. */
    val leftPx: Int,
    /** Width of this half in source pixels. */
    val widthPx: Int,
    val isLeft: Boolean,
)

/**
 * Pure, framework-free rule for deciding whether a page is a "double page" and how to split it.
 *
 * It lives in `:core:model` next to [ComicPage] so both the reader and `:core:image` can reason
 * about it without Android or Compose on the classpath.
 */
object PageSplit {

    /** A page is a double page when it is strictly wider than it is tall. */
    const val SPLIT_ASPECT_RATIO_THRESHOLD = 1.0f

    /**
     * Default gutter overlap per side, as a fraction of the full width.
     *
     * Each half reaches this far past the centre line, so the two halves share `2 * 5%` of the
     * image. This keeps the original 55%/45% split as the default behaviour.
     */
    const val DEFAULT_OVERLAP_FRACTION = 0.05f

    /** Upper bound for a user-chosen overlap, keeping each half at least 25% of the width. */
    const val MAX_OVERLAP_FRACTION = 0.25f

    /** Right edge of the left half at the default overlap (55%). Derived for compatibility. */
    const val LEFT_END_FRACTION = 0.5f + DEFAULT_OVERLAP_FRACTION

    /** Left edge of the right half at the default overlap (45%). Derived for compatibility. */
    const val RIGHT_START_FRACTION = 0.5f - DEFAULT_OVERLAP_FRACTION

    fun isDoublePage(widthPx: Int, heightPx: Int): Boolean =
        heightPx > 0 && widthPx.toFloat() / heightPx.toFloat() > SPLIT_ASPECT_RATIO_THRESHOLD

    /**
     * The two halves of a double page, or an empty list for a normal (portrait) page.
     *
     * [overlapFraction] is how far each half reaches past the centre line as a fraction of the full
     * width: `0.05` yields the historical `[0, 55%]` / `[45%, 100%]` split. Wider overlaps keep more
     * of the gutter in both halves for issues whose fold eats into the artwork.
     *
     * [rightToLeft] only changes the order of the returned list (right half first); [PageHalf.isLeft]
     * always describes the real side of the source image.
     */
    fun halves(
        widthPx: Int,
        heightPx: Int,
        overlapFraction: Float = DEFAULT_OVERLAP_FRACTION,
        rightToLeft: Boolean = false,
    ): List<PageHalf> {
        if (!isDoublePage(widthPx, heightPx)) return emptyList()
        val overlap = overlapFraction.coerceIn(0f, MAX_OVERLAP_FRACTION)
        val leftEndFraction = (0.5f + overlap).coerceAtMost(1f)
        val rightStartFraction = (0.5f - overlap).coerceAtLeast(0f)
        val leftEnd = (widthPx * leftEndFraction).toInt().coerceIn(1, widthPx)
        val rightStart = (widthPx * rightStartFraction).toInt().coerceIn(0, widthPx - 1)
        val left = PageHalf(leftPx = 0, widthPx = leftEnd, isLeft = true)
        val right = PageHalf(leftPx = rightStart, widthPx = widthPx - rightStart, isLeft = false)
        return if (rightToLeft) listOf(right, left) else listOf(left, right)
    }
}
