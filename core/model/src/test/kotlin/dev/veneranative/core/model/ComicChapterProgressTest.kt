package dev.veneranative.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComicChapterProgressTest {

    @Test fun `latest chapter check follows the reading version and chapter number instead of source order`() {
        val chapters = listOf(
            ComicChapterEntry("a-46", "第46话", "A"),
            ComicChapterEntry("a-45", "第45话", "A"),
            ComicChapterEntry("b-134", "第134话", "B"),
            ComicChapterEntry("b-133", "第133话", "B"),
        )

        assertTrue(isLatestComicChapterInReadingVersion(chapters, ComicReadingChapter("a-46", "第46话", "A")))
        assertFalse(isLatestComicChapterInReadingVersion(chapters, ComicReadingChapter("a-45", "第45话", "A")))
        assertTrue(isLatestComicChapterInReadingVersion(chapters, ComicReadingChapter("b-134", "第134话", "B")))
    }
    private val chapters = listOf(
        ComicChapterEntry("same", "第110话", "A"),
        ComicChapterEntry("same", "第111话", "B"),
        ComicChapterEntry("third", "第110话", "C"),
    )
    @Test fun unreadUsesHighestLastChapterAcrossVersions() {
        assertEquals("111话", comicChapterProgressLabel(chapters, null))
    }
    @Test fun readUsesItsVersionEvenWhenAnotherVersionIsAhead() {
        assertEquals("10/110话", comicChapterProgressLabel(chapters, ComicReadingChapter("old", "第10话", "A")))
    }
    @Test fun legacyAmbiguousIdsDoNotSelectAnotherVersion() {
        assertEquals("10/未知", comicChapterProgressLabel(chapters, ComicReadingChapter("same", "第10话", null)))
    }
    @Test fun legacyUniqueChapterIdFindsItsVersion() {
        assertEquals("110/110话", comicChapterProgressLabel(chapters, ComicReadingChapter("third", "第110话", null)))
    }
    @Test fun newestFirstAndSpecialChaptersDoNotInflateTotal() {
        val entries = listOf(ComicChapterEntry("new", "第111話 完结", "A"), ComicChapterEntry("old", "第1话", "A"), ComicChapterEntry("extra", "番外2026", "A"))
        assertEquals("111话", comicChapterProgressLabel(entries, null))
    }
    @Test fun unnumberedDirectoriesUseLargestVersionCountRatherThanSum() {
        val entries = listOf(ComicChapterEntry("a", "开篇", "A"), ComicChapterEntry("b", "终篇", "A"), ComicChapterEntry("c", "开篇", "B"))
        assertEquals("2章", comicChapterProgressLabel(entries, null))
        assertEquals("开篇/1章", comicChapterProgressLabel(entries, ComicReadingChapter("c", "开篇", "B")))
    }
    @Test fun longSpecialChapterNamesKeepTheTotalInCompactLabels() {
        assertEquals("番外特别纪…/111话", comicChapterProgressLabel(chapters, ComicReadingChapter("extra", "番外特别纪念篇下", "B")))
    }
    @Test fun removedVersionRemainsUnknown() {
        assertEquals("10/未知", comicChapterProgressLabel(chapters, ComicReadingChapter("a", "第10话", "Removed")))
    }
}
