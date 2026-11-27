package dev.veneranative.feature.sources

import dev.veneranative.core.model.SourceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.abs

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

    private fun assertVisualCentre(finger: Float, grab: Float, layoutCentre: Float) {
        val visual = layoutCentre + draggedTranslation(finger, grab, layoutCentre)
        assertEquals(finger - grab, visual, 0.0001f)
    }

    @Test
    fun `translation keeps the card under the finger while it moves`() {
        // Same layout slot, finger travels 120px: the whole travel becomes translation.
        val grab = 12f
        assertEquals(108f, draggedTranslation(120f, grab, 0f), 0.0001f)
        assertVisualCentre(240f, grab, 0f)
    }

    @Test
    fun `a swap that moves the layout centre does not shift the card under the finger`() {
        val finger = 160f
        val grab = 8f
        // First card at centre 40; after reordering onto the second slot its centre is 130.
        val before = draggedTranslation(finger, grab, 40f)
        val after = draggedTranslation(finger, grab, 130f)
        assertVisualCentre(finger, grab, 40f)
        assertVisualCentre(finger, grab, 130f)
        // The translation absorbs exactly the slot shift, so the card never jumps.
        assertEquals(before - 90f, after, 0.0001f)
        assertEquals(before - after, 90f, 0.0001f)
    }

    @Test
    fun `edge scroll changes the layout centre without moving the card under the finger`() {
        val finger = 40f
        val grab = 4f
        val centres = listOf(36f, 12f, -24f) // list scrolls, dragging card slides up
        centres.forEach { assertVisualCentre(finger, grab, it) }
        // Translation grows as the layout centre falls, i.e. it follows the scroll.
        val translations = centres.map { draggedTranslation(finger, grab, it) }
        assert(abs(translations[0] - translations[2]) > 0f)
    }
}
