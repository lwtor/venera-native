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

    /** Right edge of the left half as a fraction of the full width (55%). */
    const val LEFT_END_FRACTION = 0.55f

    /** Left edge of the right half as a fraction of the full width (45%). */
    const val RIGHT_START_FRACTION = 0.45f

    fun isDoublePage(widthPx: Int, heightPx: Int): Boolean =
        heightPx > 0 && widthPx.toFloat() / heightPx.toFloat() > SPLIT_ASPECT_RATIO_THRESHOLD

    /**
     * The left and right halves of a double page, or an empty list for a normal (portrait) page.
     *
     * The two halves share a 10% overlap: the left half spans `[0, 55%]` and the right half spans
     * `[45%, 100%]`, so the gutter is present in both.
     */
    fun halves(widthPx: Int, heightPx: Int): List<PageHalf> {
        if (!isDoublePage(widthPx, heightPx)) return emptyList()
        val leftEnd = (widthPx * LEFT_END_FRACTION).toInt().coerceAtLeast(1)
        val rightStart = (widthPx * RIGHT_START_FRACTION).toInt().coerceIn(0, widthPx - 1)
        return listOf(
            PageHalf(leftPx = 0, widthPx = leftEnd, isLeft = true),
            PageHalf(leftPx = rightStart, widthPx = widthPx - rightStart, isLeft = false),
        )
    }
}
