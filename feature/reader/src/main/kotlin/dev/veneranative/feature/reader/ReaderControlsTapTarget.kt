package dev.veneranative.feature.reader

/**
 * Long-press hotspots for the reader, expressed as viewport fractions.
 *
 * The control panel owns the central 90% of the page so it can be summoned without hunting for a
 * small spot. Splitting a page is a different intent that also starts with a long press, so it gets
 * its own, tighter hotspot inside the control one; the split check runs first while splitting is on.
 * Without that separation, widening the control hotspot would leave the split menu only the outer
 * 5% border — technically present, practically unreachable.
 */
internal object ReaderControlsTapTarget {
    private const val CONTROL_HORIZONTAL_START = 0.05f
    private const val CONTROL_HORIZONTAL_END = 0.95f
    private const val CONTROL_VERTICAL_START = 0.05f
    private const val CONTROL_VERTICAL_END = 0.95f

    private const val SPLIT_HORIZONTAL_START = 0.20f
    private const val SPLIT_HORIZONTAL_END = 0.80f
    private const val SPLIT_VERTICAL_START = 0.30f
    private const val SPLIT_VERTICAL_END = 0.70f

    /** Central 90% of the page: long press here toggles the control panel. */
    fun contains(xPx: Float, yPx: Float, widthPx: Float, heightPx: Float): Boolean =
        within(xPx, yPx, widthPx, heightPx, CONTROL_HORIZONTAL_START, CONTROL_HORIZONTAL_END, CONTROL_VERTICAL_START, CONTROL_VERTICAL_END)

    /** Central 60% x 40%: long press here opens the split menu while splitting is enabled. */
    fun containsCompact(xPx: Float, yPx: Float, widthPx: Float, heightPx: Float): Boolean =
        within(xPx, yPx, widthPx, heightPx, SPLIT_HORIZONTAL_START, SPLIT_HORIZONTAL_END, SPLIT_VERTICAL_START, SPLIT_VERTICAL_END)

    private fun within(
        xPx: Float,
        yPx: Float,
        widthPx: Float,
        heightPx: Float,
        horizontalStart: Float,
        horizontalEnd: Float,
        verticalStart: Float,
        verticalEnd: Float,
    ): Boolean {
        if (widthPx <= 0f || heightPx <= 0f) return false
        val xFraction = xPx / widthPx
        val yFraction = yPx / heightPx
        return xFraction in horizontalStart..horizontalEnd && yFraction in verticalStart..verticalEnd
    }
}
