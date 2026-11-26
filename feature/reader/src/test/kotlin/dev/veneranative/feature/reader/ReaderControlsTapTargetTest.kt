package dev.veneranative.feature.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderControlsTapTargetTest {
    @Test
    fun `control hotspot covers the central ninety percent of the page`() {
        assertTrue(ReaderControlsTapTarget.contains(500f, 500f, 1000f, 1000f))
        assertTrue(ReaderControlsTapTarget.contains(60f, 60f, 1000f, 1000f))
        assertTrue(ReaderControlsTapTarget.contains(940f, 940f, 1000f, 1000f))
    }

    @Test
    fun `control hotspot includes points just inside its edges`() {
        assertTrue(ReaderControlsTapTarget.contains(51f, 500f, 1000f, 1000f))
        assertTrue(ReaderControlsTapTarget.contains(949f, 500f, 1000f, 1000f))
        assertTrue(ReaderControlsTapTarget.contains(500f, 51f, 1000f, 1000f))
        assertTrue(ReaderControlsTapTarget.contains(500f, 949f, 1000f, 1000f))
    }

    @Test
    fun `taps outside control hotspot do not toggle controls`() {
        assertFalse(ReaderControlsTapTarget.contains(40f, 500f, 1000f, 1000f))
        assertFalse(ReaderControlsTapTarget.contains(960f, 500f, 1000f, 1000f))
        assertFalse(ReaderControlsTapTarget.contains(500f, 40f, 1000f, 1000f))
        assertFalse(ReaderControlsTapTarget.contains(500f, 960f, 1000f, 1000f))
    }

    @Test
    fun `hotspot scales consistently with viewport size`() {
        assertTrue(ReaderControlsTapTarget.contains(36f, 45f, 600f, 750f))
        assertFalse(ReaderControlsTapTarget.contains(24f, 375f, 600f, 750f))
    }

    @Test
    fun `split hotspot stays compact inside the control hotspot`() {
        assertTrue(ReaderControlsTapTarget.containsCompact(500f, 500f, 1000f, 1000f))
        assertTrue(ReaderControlsTapTarget.containsCompact(250f, 500f, 1000f, 1000f))
        assertFalse(ReaderControlsTapTarget.containsCompact(60f, 500f, 1000f, 1000f))
        // A point can toggle controls while being outside the split menu's tighter region.
        assertTrue(ReaderControlsTapTarget.contains(60f, 500f, 1000f, 1000f))
    }

    @Test
    fun `empty viewport never contains a tap`() {
        assertFalse(ReaderControlsTapTarget.contains(0f, 0f, 0f, 100f))
        assertFalse(ReaderControlsTapTarget.contains(0f, 0f, 100f, 0f))
        assertFalse(ReaderControlsTapTarget.containsCompact(0f, 0f, 0f, 100f))
    }
}
