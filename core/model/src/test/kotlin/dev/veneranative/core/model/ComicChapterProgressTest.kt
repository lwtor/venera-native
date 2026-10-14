package dev.veneranative.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ComicChapterProgressTest {
    private val chapters = listOf(
        ComicChapterEntry("same", "第110话", "A"),
        ComicChapterEntry("same", "第111话", "B"),
        ComicChapterEntry("third", "第110话", "C"),
    )
    @Test fun unreadUsesHighestLastChapterAcrossVersions() {
        assertEquals("共111话", comicChapterProgressLabel(chapters, null))
    }
    @Test fun readUsesItsVersionEvenWhenAnotherVersionIsAhead() {
        assertEquals("第10话 / 共110话", comicChapterProgressLabel(chapters, ComicReadingChapter("old", "第10话", "A")))
    }
    @Test fun legacyAmbiguousIdsDoNotSelectAnotherVersion() {
        assertEquals("第10话 / 总章节未知", comicChapterProgressLabel(chapters, ComicReadingChapter("same", "第10话", null)))
    }
    @Test fun legacyUniqueChapterIdFindsItsVersion() {
        assertEquals("第110话 / 共110话", comicChapterProgressLabel(chapters, ComicReadingChapter("third", "第110话", null)))
    }
    @Test fun newestFirstAndSpecialChaptersDoNotInflateTotal() {
        val entries = listOf(ComicChapterEntry("new", "第111話 完结", "A"), ComicChapterEntry("old", "第1话", "A"), ComicChapterEntry("extra", "番外2026", "A"))
        assertEquals("共111话", comicChapterProgressLabel(entries, null))
    }
    @Test fun unnumberedDirectoriesUseLargestVersionCountRatherThanSum() {
        val entries = listOf(ComicChapterEntry("a", "开篇", "A"), ComicChapterEntry("b", "终篇", "A"), ComicChapterEntry("c", "开篇", "B"))
        assertEquals("共2章", comicChapterProgressLabel(entries, null))
        assertEquals("开篇 / 共1章", comicChapterProgressLabel(entries, ComicReadingChapter("c", "开篇", "B")))
    }
    @Test fun removedVersionRemainsUnknown() {
        assertEquals("第10话 / 总章节未知", comicChapterProgressLabel(chapters, ComicReadingChapter("a", "第10话", "Removed")))
    }
}
