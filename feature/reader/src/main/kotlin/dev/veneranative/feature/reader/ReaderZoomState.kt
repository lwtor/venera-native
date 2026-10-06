package dev.veneranative.feature.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import dev.veneranative.core.image.tiling.PageViewport


/**
 * Pan and zoom state of the reader viewport.
 *
 * This is viewer state, not reader state: it lives inside the composition, is never put into
 * [ReaderUiState] and holds no pixels, so it stays safe to keep and to save across recreation.
 */
@Stable
class ReaderZoomState {

    var scale: Float by mutableStateOf(MIN_SCALE)
        private set

    var offsetX: Float by mutableStateOf(0f)
        private set

    var offsetY: Float by mutableStateOf(0f)
        private set

    /** True while the page is zoomed in, which is when panning must take over from scrolling. */
    val isZoomed: Boolean get() = scale > MIN_SCALE + SCALE_EPSILON

    fun applyGesture(
        centroid: Offset,
        pivot: Offset,
        pan: Offset,
        zoomFactor: Float,
        viewport: PageViewport,
        contentWidthPx: Float,
        contentHeightPx: Float,
    ) {
        val previousScale = scale
        scale = (scale * zoomFactor).coerceIn(MIN_SCALE, MAX_SCALE)
        if (!isZoomed) {
            resetOffset()
            return
        }
        val scaleChange = scale / previousScale
        applyAnchoredScale(
            scaleChange = scaleChange,
            centroid = centroid,
            pivot = pivot,
            pan = pan,
            viewport = viewport,
            contentWidthPx = contentWidthPx,
            contentHeightPx = contentHeightPx,
        )
    }

    /** Toggles between fitted size and 2x zoom, keeping the tapped point under the finger. */
    fun toggleZoomAt(
        centroid: Offset,
        pivot: Offset,
        viewport: PageViewport,
        contentWidthPx: Float,
        contentHeightPx: Float,
    ) {
        val previousScale = scale
        scale = if (isZoomed) MIN_SCALE else DOUBLE_TAP_SCALE
        if (!isZoomed) {
            resetOffset()
            return
        }
        applyAnchoredScale(
            scaleChange = scale / previousScale,
            centroid = centroid,
            pivot = pivot,
            pan = Offset.Zero,
            viewport = viewport,
            contentWidthPx = contentWidthPx,
            contentHeightPx = contentHeightPx,
        )
    }

    private fun applyAnchoredScale(
        scaleChange: Float,
        centroid: Offset,
        pivot: Offset,
        pan: Offset,
        viewport: PageViewport,
        contentWidthPx: Float,
        contentHeightPx: Float,
    ) {
        val nextWidthPx = contentWidthPx * scaleChange
        val nextHeightPx = contentHeightPx * scaleChange
        val limitXPx = ((nextWidthPx - viewport.widthPx) / 2f).coerceAtLeast(0f)
        val limitYPx = ((nextHeightPx - viewport.heightPx) / 2f).coerceAtLeast(0f)
        offsetX = (offsetX * scaleChange + (centroid.x - pivot.x) * (1f - scaleChange) + pan.x)
            .coerceIn(-limitXPx, limitXPx)
        offsetY = (offsetY * scaleChange + (centroid.y - pivot.y) * (1f - scaleChange) + pan.y)
            .coerceIn(-limitYPx, limitYPx)
    }

    private fun resetOffset() {
        offsetX = 0f
        offsetY = 0f
    }

    companion object {
        const val MIN_SCALE = 1f
        const val DOUBLE_TAP_SCALE = 2f
        const val MAX_SCALE = 5f
        private const val SCALE_EPSILON = 0.01f
    }
}

/** Remembered per page so every page keeps its own pan and zoom. */
@Composable
internal fun rememberReaderZoomState(): ReaderZoomState = remember { ReaderZoomState() }
