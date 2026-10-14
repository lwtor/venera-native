package dev.veneranative.core.image.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DominantCoverColorTest {
    @Test fun transparentPixelsDoNotBiasTheDominantColor() {
        assertEquals(0xffff0000.toInt(), dominantCoverColor(intArrayOf(0, 0, 0xffff0000.toInt())))
        assertNull(dominantCoverColor(intArrayOf(0)))
    }
    @Test fun dominantClusterWinsOverRareAccent() {
        assertEquals(0xff102030.toInt(), dominantCoverColor(intArrayOf(0xff102030.toInt(), 0xff102030.toInt(), 0xffff0000.toInt())))
    }
}
