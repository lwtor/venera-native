package dev.veneranative.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * `ComicKey` has to be used end to end: a chapter is only identified by the source, the comic and
 * the source's own chapter id together.
 */
class ChapterKeyTest {

    private val sourceA = SourceId("source-a")
    private val sourceB = SourceId("source-b")

    @Test
    fun `the same chapter id under different comics is a different key`() {
        val first = chapterKey(sourceA, "comic-1", "chapter-1")
        val second = chapterKey(sourceA, "comic-2", "chapter-1")

        assertNotEquals(first, second)
    }

    @Test
    fun `the same comic and chapter ids from different sources are different keys`() {
        val first = chapterKey(sourceA, "comic-1", "chapter-1")
        val second = chapterKey(sourceB, "comic-1", "chapter-1")

        assertNotEquals(first, second)
    }

    @Test
    fun `a chapter keeps the comic key it belongs to`() {
        val key = chapterKey(sourceA, "comic-1", "chapter-1")
        val chapter = Chapter(key = key, title = "Chapter 1", index = 0)

        assertEquals("comic-1", chapter.key.comicKey.remoteId.value)
        assertEquals("source-a", chapter.key.comicKey.sourceId.value)
    }

    private fun chapterKey(sourceId: SourceId, comicId: String, chapterId: String): ChapterKey =
        ChapterKey(
            comicKey = ComicKey(sourceId, RemoteComicId(comicId)),
            remoteId = RemoteChapterId(chapterId),
        )
}
