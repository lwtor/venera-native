package dev.veneranative.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageSplitTest {

    @Test
    fun `wide page is a double page`() {
        assertTrue(PageSplit.isDoublePage(widthPx = 2000, heightPx = 1400))
    }

    @Test
    fun `portrait page is not a double page`() {
        assertFalse(PageSplit.isDoublePage(widthPx = 1080, heightPx = 1440))
    }

    @Test
    fun `square page is not a double page`() {
        assertFalse(PageSplit.isDoublePage(widthPx = 1000, heightPx = 1000))
    }

    @Test
    fun `zero or negative height never splits`() {
        assertFalse(PageSplit.isDoublePage(widthPx = 2000, heightPx = 0))
        assertFalse(PageSplit.isDoublePage(widthPx = 2000, heightPx = -1))
    }

    @Test
    fun `non double page produces no halves`() {
        assertEquals(emptyList<PageHalf>(), PageSplit.halves(widthPx = 1080, heightPx = 1440))
    }

    @Test
    fun `double page produces left then right halves with 10 percent overlap`() {
        val halves = PageSplit.halves(widthPx = 2000, heightPx = 1400)

        assertEquals(2, halves.size)
        val left = halves[0]
        val right = halves[1]

        assertTrue(left.isLeft)
        assertFalse(right.isLeft)
        assertEquals(0, left.leftPx)
        assertEquals(1100, left.widthPx) // 55% of 2000
        assertEquals(900, right.leftPx) // 45% of 2000
        assertEquals(1100, right.widthPx) // 2000 - 900

        // The shared gutter spans [900, 1100).
        assertTrue(right.leftPx < left.leftPx + left.widthPx)
    }

    @Test
    fun `halves span from zero to the full source width`() {
        val width = 2000
        val halves = PageSplit.halves(widthPx = width, heightPx = 1400)
        assertEquals(0, halves[0].leftPx)
        assertEquals(width, halves[1].leftPx + halves[1].widthPx)
    }
}
