package dev.veneranative.feature.details

import dev.veneranative.core.model.Comic
import dev.veneranative.core.model.ComicDetail
import dev.veneranative.core.model.ComicKey
import dev.veneranative.core.model.RemoteChapterId
import dev.veneranative.core.model.RemoteComicId
import dev.veneranative.core.model.SourceId
import dev.veneranative.core.model.groupedChaptersOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterLocationTest {
    @Test fun `directory start includes controls but excludes hero and metadata`() {
        assertEquals(2, state.chapterDirectoryListIndex())
        assertEquals(2, state.copy(order = ChapterOrder.Reversed, selectedGroup = "JP").chapterDirectoryListIndex())
        assertEquals(5, state.copy(status = DetailsStatus.Failed, detail = state.detail!!.copy(
            thumbnails = listOf("https://fixture.invalid/image"), sourceUrl = "https://fixture.invalid/comic",
        )).chapterDirectoryListIndex())
    }

    @Test fun `visibility requires overlap with viewport below the toolbar`() {
        assertTrue(chapterRowIsVisible(300, 100, 800, 80))
        assertTrue(chapterRowIsVisible(40, 60, 800, 80))
        assertTrue(chapterRowIsVisible(780, 100, 800, 80))
        assertFalse(chapterRowIsVisible(20, 60, 800, 80))
        assertFalse(chapterRowIsVisible(800, 100, 800, 80))
        assertFalse(chapterRowIsVisible(100, 0, 800, 80))
    }

    @Test fun `chapter row centers between toolbar and bottom action`() {
        assertEquals(-410, chapterCenterScrollOffset(viewportHeight = 900, toolbarHeight = 80, rowHeight = 160))
        assertEquals(-200, chapterCenterScrollOffset(viewportHeight = 500, toolbarHeight = 80, rowHeight = 180))
    }

    @Test fun `oversized row stays below toolbar in a short viewport`() {
        assertEquals(-80, chapterCenterScrollOffset(viewportHeight = 200, toolbarHeight = 80, rowHeight = 240))
    }

    private val comicKey = ComicKey(SourceId("fixture"), RemoteComicId("comic"))
    private val chapters = groupedChaptersOf(comicKey, linkedMapOf(
        "EN" to linkedMapOf("1" to "One", "2" to "Two", "3" to "Three", "4" to "Four"),
        "JP" to linkedMapOf("5" to "Five", "6" to "Six"),
    ))
    private val state = DetailsUiState(
        status = DetailsStatus.Ready,
        detail = ComicDetail(Comic(comicKey, "Fixture"), chapters = chapters),
        lastReadChapterId = RemoteChapterId("5"),
    )

    @Test fun `index accounts for grid rows and version headings in either order`() {
        assertEquals(7, state.currentChapterListIndex())
        assertEquals(4, state.copy(order = ChapterOrder.Reversed).currentChapterListIndex())
        assertEquals(3, state.copy(selectedGroup = "JP").currentChapterListIndex())
        assertEquals(5, state.copy(lastReadChapterId = RemoteChapterId("4")).currentChapterListIndex())
    }

    @Test fun `optional content and refresh failure shift the directory index`() {
        val detailed = state.copy(status = DetailsStatus.Failed, detail = state.detail!!.copy(
            thumbnails = listOf("https://fixture.invalid/image"), sourceUrl = "https://fixture.invalid/comic",
        ))
        assertEquals(10, detailed.currentChapterListIndex())
        assertEquals(9, detailed.copy(detail = detailed.detail!!.copy(sourceUrl = "invalid")).currentChapterListIndex())
    }

    @Test fun `no history missing chapter or hidden target produces no index`() {
        assertEquals(null, state.copy(lastReadChapterId = null).currentChapterListIndex())
        assertEquals(null, state.copy(lastReadChapterId = RemoteChapterId("removed")).currentReadingChapter)
        assertEquals(null, state.copy(selectedGroup = "EN").currentChapterListIndex())
        assertEquals(null, state.copy(chapterQuery = "One").currentChapterListIndex())
    }
}
