package dev.veneranative.data.history

import dev.veneranative.core.model.ComicKey
import dev.veneranative.core.model.RemoteChapterId
import dev.veneranative.core.model.RemoteComicId
import dev.veneranative.core.model.SourceId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DefaultHistoryRepositoryTest {

    private val historyDao = FakeReadingHistoryDao()
    private val progressDao = FakeReadingProgressDao()
    private val repository = DefaultHistoryRepository(historyDao, progressDao)

    private val key = ComicKey(SourceId("source-a"), RemoteComicId("comic-1"))

    private fun entry(
        chapterId: String = "chapter-1",
        pageIndex: Int = 3,
        updatedAt: Long = 1_000L,
    ) = ReadingHistoryEntry(
        comicKey = key,
        comicTitle = "Comic One",
        chapterId = RemoteChapterId(chapterId),
        chapterTitle = "Chapter One",
        coverUrl = "https://images.example/cover.webp",
        pageIndex = pageIndex,
        pageCount = 20,
        updatedAtEpochMillis = updatedAt,
    )

    @Test fun `resume belongs only to the saved chapter and records display metadata`() = runTest {
        repository.record(entry(chapterId = "chapter-1", pageIndex = 3))
        val tracker = ReadingProgressTracker(repository, this, { 2_000L })
        val matching = ReaderProgressSession(
            dev.veneranative.core.model.ChapterKey(key, RemoteChapterId("chapter-1")), repository, tracker, { 2_000L },
        )
        val other = ReaderProgressSession(
            dev.veneranative.core.model.ChapterKey(key, RemoteChapterId("chapter-2")), repository, tracker, { 2_000L },
        )
        assertEquals(3, matching.resumePage())
        assertEquals(0, other.resumePage())
        other.record(dev.veneranative.core.model.ChapterContent(
            "Second chapter", listOf(dev.veneranative.core.model.ComicPage(0, "image", 10, 10)),
            comicTitle = "Readable title", coverUrl = "cover",
        ), 0)
        tracker.flush()
        val recent = repository.observeRecent(1).first().first()
        assertEquals("Readable title", recent.comicTitle)
        assertEquals("Second chapter", recent.chapterTitle)
        assertEquals("cover", recent.coverUrl)
    }

    @Test fun `recording writes to both history and progress`() = runTest {
        repository.record(entry())

        assertEquals(1, historyDao.rows.value.size)
        assertEquals(1, progressDao.rows.value.size)
    }

    @Test fun `recording the same comic and chapter replaces the row instead of duplicating it`() =
        runTest {
            repository.record(entry(pageIndex = 3, updatedAt = 1_000L))
            repository.record(entry(pageIndex = 7, updatedAt = 2_000L))

            assertEquals(1, historyDao.rows.value.size)
            assertEquals(7, historyDao.rows.value.single().pageIndex)
        }

    @Test fun `different chapters of the same comic stay separate history rows`() = runTest {
        repository.record(entry(chapterId = "chapter-1"))
        repository.record(entry(chapterId = "chapter-2"))

        assertEquals(2, historyDao.rows.value.size)
    }

    @Test fun `the same comic id under another source is another history row`() = runTest {
        repository.record(entry())
        val otherKey = ComicKey(SourceId("source-b"), RemoteComicId("comic-1"))
        repository.record(entry().copy(comicKey = otherKey))

        assertEquals(2, historyDao.rows.value.size)
    }

    @Test fun `progress answers the chapter and page to resume at`() = runTest {
        repository.record(entry(chapterId = "chapter-5", pageIndex = 12))

        val progress = repository.progress(key)

        assertEquals(RemoteChapterId("chapter-5"), progress?.chapterId)
        assertEquals(12, progress?.pageIndex)
    }

    @Test fun `progress is nothing for a comic that was never opened`() = runTest {
        assertNull(repository.progress(key))
    }

    @Test fun `the latest page wins even when written out of order`() = runTest {
        repository.record(entry(pageIndex = 9, updatedAt = 5_000L))
        repository.record(entry(pageIndex = 2, updatedAt = 1_000L))

        assertEquals(2, repository.progress(key)?.pageIndex)
    }

    @Test fun `recents are newest first and honour the limit`() = runTest {
        repository.record(entry(chapterId = "c1", updatedAt = 1_000L))
        repository.record(entry(chapterId = "c2", updatedAt = 3_000L))
        repository.record(entry(chapterId = "c3", updatedAt = 2_000L))

        val recent = repository.observeRecent(limit = 2).first()

        assertEquals(listOf("c2", "c3"), recent.map { it.chapterId.value })
    }

    @Test fun `removing drops every chapter row of that comic`() = runTest {
        repository.record(entry(chapterId = "c1"))
        repository.record(entry(chapterId = "c2"))

        repository.remove(key)

        assertEquals(0, historyDao.rows.value.size)
    }

    @Test fun `a malformed row does not fail the whole recent list`() = runTest {
        historyDao.rows.value = listOf(
            dev.veneranative.core.database.ReadingHistoryEntity(
                sourceId = "",
                comicId = "comic-1",
                chapterId = "chapter-1",
                comicTitle = "Broken",
                chapterTitle = "Broken",
                coverUrl = null,
                pageIndex = 0,
                pageCount = 1,
                updatedAtEpochMillis = 1L,
            ),
        )

        assertEquals(0, repository.observeRecent(limit = 10).first().size)
    }
}
