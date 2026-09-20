package dev.veneranative.feature.details

import dev.veneranative.core.model.Chapter
import dev.veneranative.core.model.Comic
import dev.veneranative.core.model.ComicDetail
import dev.veneranative.core.model.ComicKey
import dev.veneranative.core.model.RemoteComicId
import dev.veneranative.core.model.SourceId
import dev.veneranative.core.model.chaptersOf
import dev.veneranative.core.model.groupedChaptersOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The chapter list's presentation rules, as pure functions.
 *
 * These are what a source's own ordering has to survive: `Chapter.index` is a position in the order
 * the source declared, so nothing here re-sorts by title or number.
 */
class DetailsChaptersTest {

    private val comicKey = ComicKey(SourceId("s"), RemoteComicId("c1"))

    @Test
    fun `a flat source declares no groups`() {
        val state = ready(chaptersOf(comicKey, linkedMapOf("1" to "Chapter 1", "2" to "Chapter 2")))

        assertTrue(state.groups.isEmpty())
        assertFalse(state.groupsTheList)
        assertEquals(listOf("Chapter 1", "Chapter 2"), state.visibleChapters.map { it.title })
    }

    @Test
    fun `groups keep the order the source declared them in`() {
        val state = ready(
            groupedChaptersOf(
                comicKey,
                linkedMapOf(
                    "Volume 2 - EN" to linkedMapOf("en" to "Chapter 1"),
                    "Volume 1 - JP" to linkedMapOf("jp" to "第1話"),
                ),
            ),
        )

        assertEquals(listOf("Volume 2 - EN", "Volume 1 - JP"), state.groups)
        assertTrue(state.groupsTheList)
    }

    @Test
    fun `selecting a group narrows the list and stops the headers`() {
        val state = ready(
            groupedChaptersOf(
                comicKey,
                linkedMapOf(
                    "EN" to linkedMapOf("en1" to "Chapter 1", "en2" to "Chapter 2"),
                    "JP" to linkedMapOf("jp1" to "第1話"),
                ),
            ),
            selectedGroup = "JP",
        )

        assertEquals(listOf("第1話"), state.visibleChapters.map { it.title })
        assertFalse("one group needs no headers", state.groupsTheList)
    }

    @Test
    fun `reversing flips the display without regrouping`() {
        val state = ready(
            groupedChaptersOf(
                comicKey,
                linkedMapOf(
                    "EN" to linkedMapOf("en1" to "Chapter 1", "en2" to "Chapter 2"),
                    "JP" to linkedMapOf("jp1" to "第1話"),
                ),
            ),
            order = ChapterOrder.Reversed,
        )

        // The source groups chapters together, so the reverse of its order keeps each group intact.
        assertEquals(listOf("第1話", "Chapter 2", "Chapter 1"), state.visibleChapters.map { it.title })
    }

    @Test
    fun `a comic without chapters is not a failure`() {
        val state = ready(emptyList())

        assertTrue(state.hasNoChapters)
        assertTrue(state.visibleChapters.isEmpty())

        val failed = state.copy(status = DetailsStatus.Failed)
        assertFalse("a failed load is not a partial result", failed.hasNoChapters)
    }

    private fun ready(
        chapters: List<Chapter>,
        selectedGroup: String? = null,
        order: ChapterOrder = ChapterOrder.SourceOrder,
    ) = DetailsUiState(
        status = DetailsStatus.Ready,
        detail = ComicDetail(comic = Comic(comicKey, title = "Frieren"), chapters = chapters),
        selectedGroup = selectedGroup,
        order = order,
    )
}
