package dev.veneranative.feature.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import dev.veneranative.feature.reader.image.PageViewport

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
        pan: Offset,
        zoomFactor: Float,
        viewport: PageViewport,
        contentWidthPx: Float,
        contentHeightPx: Float,
    ) {
        scale = (scale * zoomFactor).coerceIn(MIN_SCALE, MAX_SCALE)
        if (!isZoomed) {
            resetOffset()
            return
        }
        val limitXPx = ((contentWidthPx - viewport.widthPx) / 2f).coerceAtLeast(0f)
        val limitYPx = ((contentHeightPx - viewport.heightPx) / 2f).coerceAtLeast(0f)
        offsetX = (offsetX + pan.x).coerceIn(-limitXPx, limitXPx)
        offsetY = (offsetY + pan.y).coerceIn(-limitYPx, limitYPx)
    }

    private fun resetOffset() {
        offsetX = 0f
        offsetY = 0f
    }

    companion object {
        const val MIN_SCALE = 1f
        const val MAX_SCALE = 5f
        private const val SCALE_EPSILON = 0.01f
    }
}

/** Remembered per page so every page keeps its own pan and zoom. */
@Composable
internal fun rememberReaderZoomState(): ReaderZoomState = remember { ReaderZoomState() }
