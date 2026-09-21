package dev.veneranative.data.history

import dev.veneranative.core.model.ComicKey
import dev.veneranative.core.model.RemoteChapterId
import dev.veneranative.core.model.RemoteComicId
import dev.veneranative.core.model.SourceId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

/**
 * The guarantee under test is the one that matters when reading: **the newest page always survives**.
 *
 * Throttling is allowed to drop intermediate writes, because nobody cares that page 4 was recorded
 * on the way to page 9. It is never allowed to lose the last one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReadingProgressTrackerTest {

    private val historyDao = FakeReadingHistoryDao()
    private val progressDao = FakeReadingProgressDao()
    private val repository = DefaultHistoryRepository(historyDao, progressDao)

    private fun entry(pageIndex: Int, updatedAt: Long = 0L) = ReadingHistoryEntry(
        comicKey = ComicKey(SourceId("source-a"), RemoteComicId("comic-1")),
        comicTitle = "Comic One",
        chapterId = RemoteChapterId("chapter-1"),
        chapterTitle = "Chapter One",
        coverUrl = null,
        pageIndex = pageIndex,
        pageCount = 20,
        updatedAtEpochMillis = updatedAt,
    )

    @Test fun `the first page change writes immediately`() = runTest {
        val tracker = tracker(this, throttleMillis = 2_000L)

        tracker.onPageChanged(entry(pageIndex = 1))
        advanceUntilIdle()

        assertEquals(1, progressDao.rows.value.single().pageIndex)
    }

    @Test fun `changes inside the throttle window do not write again`() = runTest {
        val clock = AtomicLong(0L)
        val tracker = tracker(this, throttleMillis = 2_000L, clock = clock)

        tracker.onPageChanged(entry(pageIndex = 1))
        advanceUntilIdle()
        clock.set(500L)
        tracker.onPageChanged(entry(pageIndex = 2))
        advanceUntilIdle()
        clock.set(1_000L)
        tracker.onPageChanged(entry(pageIndex = 3))
        advanceUntilIdle()

        assertEquals(1, progressDao.rows.value.single().pageIndex)
    }

    @Test fun `once the window elapses the next change writes`() = runTest {
        val clock = AtomicLong(0L)
        val tracker = tracker(this, throttleMillis = 2_000L, clock = clock)

        tracker.onPageChanged(entry(pageIndex = 1))
        advanceUntilIdle()
        clock.set(500L)
        tracker.onPageChanged(entry(pageIndex = 2))
        advanceUntilIdle()

        clock.set(2_500L)
        tracker.onPageChanged(entry(pageIndex = 3))
        advanceUntilIdle()

        assertEquals(3, progressDao.rows.value.single().pageIndex)
    }

    @Test fun `flush writes the newest pending page even inside the window`() = runTest {
        val clock = AtomicLong(0L)
        val tracker = tracker(this, throttleMillis = 10_000L, clock = clock)

        tracker.onPageChanged(entry(pageIndex = 1))
        tracker.onPageChanged(entry(pageIndex = 2))
        tracker.onPageChanged(entry(pageIndex = 9))
        tracker.flush()

        assertEquals(9, progressDao.rows.value.single().pageIndex)
    }

    @Test fun `flush with nothing pending does not write`() = runTest {
        val tracker = tracker(this)

        tracker.flush()

        assertEquals(0, progressDao.rows.value.size)
    }

    @Test fun `flush twice writes once, because the pending value is consumed`() = runTest {
        val tracker = tracker(this, throttleMillis = 10_000L)

        tracker.onPageChanged(entry(pageIndex = 4))
        tracker.flush()
        tracker.flush()

        assertEquals(1, progressDao.rows.value.size)
    }

    @Test fun `a later page never loses to an earlier one`() = runTest {
        val tracker = tracker(this, throttleMillis = 10_000L)

        tracker.onPageChanged(entry(pageIndex = 2))
        tracker.onPageChanged(entry(pageIndex = 15))
        tracker.flush()

        assertEquals(15, progressDao.rows.value.single().pageIndex)
    }

    @Test fun `history and progress stay in sync after a flush`() = runTest {
        val tracker = tracker(this, throttleMillis = 10_000L)

        tracker.onPageChanged(entry(pageIndex = 6))
        tracker.flush()

        assertEquals(6, historyDao.rows.value.single().pageIndex)
        assertEquals(6, progressDao.rows.value.single().pageIndex)
    }

    private fun tracker(
        scope: CoroutineScope,
        throttleMillis: Long = 2_000L,
        clock: AtomicLong = AtomicLong(0L),
    ) = ReadingProgressTracker(
        repository = repository,
        scope = scope,
        clock = clock::get,
        throttleMillis = throttleMillis,
    )
}
