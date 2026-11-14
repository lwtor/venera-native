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

    @Test
    fun `default arguments keep the historical 55 and 45 percent split`() {
        val halves = PageSplit.halves(widthPx = 2000, heightPx = 1400)

        assertEquals(0, halves[0].leftPx)
        assertEquals(1100, halves[0].widthPx)
        assertEquals(900, halves[1].leftPx)
        assertEquals(1100, halves[1].widthPx)
        assertEquals(PageSplit.LEFT_END_FRACTION, 0.55f)
        assertEquals(PageSplit.RIGHT_START_FRACTION, 0.45f)
    }

    @Test
    fun `zero overlap makes the halves meet without sharing pixels`() {
        val halves = PageSplit.halves(widthPx = 2000, heightPx = 1400, overlapFraction = 0f)

        val left = halves[0]
        val right = halves[1]
        assertEquals(1000, left.widthPx)
        assertEquals(1000, right.leftPx)
        assertEquals(left.leftPx + left.widthPx, right.leftPx)
    }

    @Test
    fun `ten percent per-side overlap shares twenty percent of the width`() {
        val width = 2000
        val halves = PageSplit.halves(widthPx = width, heightPx = 1400, overlapFraction = 0.1f)

        val left = halves[0]
        val right = halves[1]
        assertEquals(1200, left.widthPx) // 60%
        assertEquals(800, right.leftPx) // 40%

        val sharedPx = left.leftPx + left.widthPx - right.leftPx
        assertEquals((width * 0.2f).toInt(), sharedPx)
    }

    @Test
    fun `right to left only reorders the halves`() {
        val normal = PageSplit.halves(widthPx = 2000, heightPx = 1400)
        val reversed = PageSplit.halves(widthPx = 2000, heightPx = 1400, rightToLeft = true)

        assertEquals(2, reversed.size)
        assertFalse(reversed[0].isLeft)
        assertTrue(reversed[1].isLeft)
        assertEquals(normal[1], reversed[0])
        assertEquals(normal[0], reversed[1])
        // `isLeft` still describes the real side of the source image.
        assertEquals(900, reversed[0].leftPx)
        assertEquals(0, reversed[1].leftPx)
    }

    @Test
    fun `overlap is clamped to the maximum`() {
        val halves = PageSplit.halves(widthPx = 2000, heightPx = 1400, overlapFraction = 0.9f)
        val clamped = PageSplit.halves(
            widthPx = 2000,
            heightPx = 1400,
            overlapFraction = PageSplit.MAX_OVERLAP_FRACTION,
        )

        assertEquals(clamped, halves)
    }
}
