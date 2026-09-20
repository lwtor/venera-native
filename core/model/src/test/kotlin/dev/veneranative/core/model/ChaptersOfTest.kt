package dev.veneranative.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Chapter list building, checked against the two shapes real sources use.
 *
 * The flat shape is what the protocol documents; the grouped shape is what a real source returns
 * (a map of `"Volume 1 - EN"` to its own chapter map), so both must be supported and neither may be
 * re-sorted by the app.
 */
class ChaptersOfTest {

    private val comicKey = ComicKey(SourceId("source-a"), RemoteComicId("comic-1"))

    @Test
    fun `a flat chapter map keeps the order the source declared`() {
        val chapters = chaptersOf(
            comicKey,
            linkedMapOf("ch-2" to "Chapter 2", "ch-1" to "Chapter 1"),
        )

        assertEquals(listOf("Chapter 2", "Chapter 1"), chapters.map { it.title })
        assertEquals(listOf(0, 1), chapters.map { it.index })
        assertEquals(listOf("ch-2", "ch-1"), chapters.map { it.key.remoteId.value })
        assertNull(chapters.first().group)
    }

    @Test
    fun `a grouped chapter map keeps groups and numbers the flattened list`() {
        val chapters = groupedChaptersOf(
            comicKey,
            linkedMapOf(
                "Volume 1 - EN" to linkedMapOf("ch-1-en" to "Chapter 1"),
                "Volume 1 - CN" to linkedMapOf("ch-1-cn" to "Chapter 1", "ch-2-cn" to "Chapter 2"),
            ),
        )

        assertEquals(listOf("Chapter 1", "Chapter 1", "Chapter 2"), chapters.map { it.title })
        assertEquals(listOf("Volume 1 - EN", "Volume 1 - CN", "Volume 1 - CN"), chapters.map { it.group })
        assertEquals(listOf(0, 1, 2), chapters.map { it.index })
        assertEquals(listOf("ch-1-en", "ch-1-cn", "ch-2-cn"), chapters.map { it.key.remoteId.value })
    }

    @Test
    fun `every chapter belongs to the requested comic`() {
        val chapters = groupedChaptersOf(
            comicKey,
            linkedMapOf("Volume 1 - EN" to linkedMapOf("ch-1" to "Chapter 1")),
        )

        assertEquals(listOf(comicKey), chapters.map { it.key.comicKey })
    }

    @Test
    fun `an empty group contributes no chapters but does not break numbering`() {
        val chapters = groupedChaptersOf(
            comicKey,
            linkedMapOf(
                "Volume 1 - EN" to emptyMap(),
                "Volume 2 - EN" to linkedMapOf("ch-3" to "Chapter 3"),
            ),
        )

        assertEquals(1, chapters.size)
        assertEquals(0, chapters.single().index)
        assertEquals("Volume 2 - EN", chapters.single().group)
    }
}
