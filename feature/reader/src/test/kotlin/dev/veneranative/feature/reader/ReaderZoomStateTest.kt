package dev.veneranative.feature.reader

import androidx.compose.ui.geometry.Offset
import dev.veneranative.core.image.tiling.PageViewport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderZoomStateTest {
    private val viewport = PageViewport(100, 100)

    @Test fun `pan is ignored at minimum scale so parent scrolling stays available`() {
        val state = ReaderZoomState()
        state.applyGesture(Offset(40f, 50f), 1f, viewport, 100f, 300f)
        assertFalse(state.isZoomed)
        assertEquals(0f, state.offsetX)
        assertEquals(0f, state.offsetY)
    }

    @Test fun `zoomed content pans within its current bounds`() {
        val state = ReaderZoomState()
        state.applyGesture(Offset.Zero, 2f, viewport, 100f, 100f)
        state.applyGesture(Offset(500f, -500f), 1f, viewport, 200f, 200f)
        assertTrue(state.isZoomed)
        assertEquals(50f, state.offsetX)
        assertEquals(-50f, state.offsetY)
    }

    @Test fun `zooming back to minimum resets translation`() {
        val state = ReaderZoomState()
        state.applyGesture(Offset.Zero, 2f, viewport, 100f, 100f)
        state.applyGesture(Offset(20f, 20f), 1f, viewport, 200f, 200f)
        state.applyGesture(Offset.Zero, 0.1f, viewport, 200f, 200f)
        assertEquals(1f, state.scale)
        assertEquals(0f, state.offsetX)
        assertEquals(0f, state.offsetY)
    }
}
