package dev.veneranative.feature.sources

import dev.veneranative.core.model.SourceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The drag gesture hands us layout keys, not positions in the data list. A banner above the sources
 * shifts every layout index by one, so reordering by index removed the wrong entry and duplicated
 * the dragged card. These tests pin the reorder to source identity.
 */
class SourceReorderTest {

    private fun order(vararg ids: String) = ids.map(::SourceId)

    @Test
    fun `dragging the first source onto the second keeps every source exactly once`() {
        assertEquals(
            order("b", "a", "c"),
            reorderedSourceIds(order("a", "b", "c"), SourceId("a"), SourceId("b")),
        )
    }

    @Test
    fun `dragging down several slots lands on the target slot`() {
        assertEquals(
            order("b", "c", "a", "d"),
            reorderedSourceIds(order("a", "b", "c", "d"), SourceId("a"), SourceId("c")),
        )
    }

    @Test
    fun `dragging up moves the source above the target`() {
        assertEquals(
            order("a", "c", "b", "d"),
            reorderedSourceIds(order("a", "b", "c", "d"), SourceId("c"), SourceId("b")),
        )
    }

    @Test
    fun `a banner key is not a source and is ignored`() {
        assertNull(
            reorderedSourceIds(order("a", "b"), SourceId("a"), SourceId("update-all-banner")),
        )
    }

    @Test
    fun `dropping on itself is a no-op`() {
        assertNull(reorderedSourceIds(order("a", "b"), SourceId("a"), SourceId("a")))
    }

    @Test
    fun `an unknown dragged id is a no-op`() {
        assertNull(reorderedSourceIds(order("a", "b"), SourceId("gone"), SourceId("b")))
    }
}
