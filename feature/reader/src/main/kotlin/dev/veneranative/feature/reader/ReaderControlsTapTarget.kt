package dev.veneranative.feature.reader

/** Center hotspot for toggling the reader controls, expressed as viewport fractions. */
internal object ReaderControlsTapTarget {
    private const val HORIZONTAL_START = 0.20f
    private const val HORIZONTAL_END = 0.80f
    private const val VERTICAL_START = 0.30f
    private const val VERTICAL_END = 0.70f

    fun contains(xPx: Float, yPx: Float, widthPx: Float, heightPx: Float): Boolean {
        if (widthPx <= 0f || heightPx <= 0f) return false
        val xFraction = xPx / widthPx
        val yFraction = yPx / heightPx
        return xFraction in HORIZONTAL_START..HORIZONTAL_END &&
            yFraction in VERTICAL_START..VERTICAL_END
    }
}
