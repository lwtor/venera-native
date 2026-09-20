package dev.veneranative.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pagination semantics taken from the upstream source protocol (see ADR-0007).
 *
 * The important rule is that "there is no next page" is always `next == null`: a page-numbered
 * source reports its page count, and the last page must not advertise another one, otherwise
 * callers loop on the final page forever.
 */
class PagingTest {

    @Test
    fun `cursor result without a token has no next page`() {
        val result = PagedResult.cursor(items = listOf("a", "b"), nextToken = null)

        assertFalse(result.hasMore)
        assertNull(result.next)
    }

    @Test
    fun `cursor result with a token points at the next page`() {
        val result = PagedResult.cursor(items = listOf("a"), nextToken = "page-2")

        assertTrue(result.hasMore)
        assertEquals(PageCursor.Token("page-2"), result.next)
    }

    @Test
    fun `an empty token is treated as the end of the list`() {
        val result = PagedResult.cursor(items = emptyList<String>(), nextToken = "")

        assertFalse(result.hasMore)
    }

    @Test
    fun `a middle page points at the following page number`() {
        val result = PagedResult.page(items = listOf("a"), pageNumber = 2, totalPages = 5)

        assertEquals(PageCursor.Page(3), result.next)
        assertEquals(5, result.totalPages)
        assertTrue(result.hasMore)
    }

    @Test
    fun `the last page of a numbered source has no next page`() {
        val result = PagedResult.page(items = listOf("z"), pageNumber = 5, totalPages = 5)

        assertFalse(result.hasMore)
        assertNull(result.next)
    }

    @Test
    fun `a numbered source that does not report a page count ends after one page`() {
        val result = PagedResult.page(items = listOf("a"), pageNumber = 1, totalPages = null)

        assertFalse(result.hasMore)
    }
}
