package dev.veneranative.feature.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderControlsTapTargetTest {
    @Test
    fun `center hotspot toggles controls`() {
        assertTrue(ReaderControlsTapTarget.contains(500f, 500f, 1000f, 1000f))
    }

    @Test
    fun `expanded center hotspot includes points near its edges`() {
        assertTrue(ReaderControlsTapTarget.contains(250f, 500f, 1000f, 1000f))
        assertTrue(ReaderControlsTapTarget.contains(750f, 500f, 1000f, 1000f))
        assertTrue(ReaderControlsTapTarget.contains(500f, 350f, 1000f, 1000f))
        assertTrue(ReaderControlsTapTarget.contains(500f, 650f, 1000f, 1000f))
    }

    @Test
    fun `taps outside expanded center hotspot do not toggle controls`() {
        assertFalse(ReaderControlsTapTarget.contains(190f, 500f, 1000f, 1000f))
        assertFalse(ReaderControlsTapTarget.contains(810f, 500f, 1000f, 1000f))
        assertFalse(ReaderControlsTapTarget.contains(500f, 290f, 1000f, 1000f))
        assertFalse(ReaderControlsTapTarget.contains(500f, 710f, 1000f, 1000f))
    }

    @Test
    fun `hotspot scales consistently with viewport size`() {
        assertTrue(ReaderControlsTapTarget.contains(234f, 375f, 600f, 750f))
        assertFalse(ReaderControlsTapTarget.contains(114f, 375f, 600f, 750f))
    }

    @Test
    fun `empty viewport never contains a tap`() {
        assertFalse(ReaderControlsTapTarget.contains(0f, 0f, 0f, 100f))
        assertFalse(ReaderControlsTapTarget.contains(0f, 0f, 100f, 0f))
    }
}
