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
    fun `ordinary taps near the former hotspot edges do not toggle controls`() {
        assertFalse(ReaderControlsTapTarget.contains(300f, 500f, 1000f, 1000f))
        assertFalse(ReaderControlsTapTarget.contains(700f, 500f, 1000f, 1000f))
        assertFalse(ReaderControlsTapTarget.contains(500f, 300f, 1000f, 1000f))
        assertFalse(ReaderControlsTapTarget.contains(500f, 700f, 1000f, 1000f))
    }

    @Test
    fun `hotspot scales consistently with viewport size`() {
        assertTrue(ReaderControlsTapTarget.contains(234f, 375f, 600f, 750f))
        assertFalse(ReaderControlsTapTarget.contains(180f, 375f, 600f, 750f))
    }

    @Test
    fun `empty viewport never contains a tap`() {
        assertFalse(ReaderControlsTapTarget.contains(0f, 0f, 0f, 100f))
        assertFalse(ReaderControlsTapTarget.contains(0f, 0f, 100f, 0f))
    }
}
