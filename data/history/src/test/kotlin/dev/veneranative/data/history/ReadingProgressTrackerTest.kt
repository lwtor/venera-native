package dev.veneranative.data.history

import dev.veneranative.core.model.ComicKey
import dev.veneranative.core.model.RemoteChapterId
import dev.veneranative.core.model.RemoteComicId
import dev.veneranative.core.model.SourceId
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
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
        runCurrent()

        assertEquals(1, progressDao.rows.value.single().pageIndex)
    }

    @Test fun `changes inside the throttle window do not write again`() = runTest {
        val clock = AtomicLong(0L)
        val tracker = tracker(this, throttleMillis = 2_000L, clock = clock)

        tracker.onPageChanged(entry(pageIndex = 1))
        runCurrent()
        clock.set(500L)
        tracker.onPageChanged(entry(pageIndex = 2))
        runCurrent()
        clock.set(1_000L)
        tracker.onPageChanged(entry(pageIndex = 3))
        runCurrent()

        assertEquals(1, progressDao.rows.value.single().pageIndex)
    }

    @Test fun `once the window elapses the next change writes`() = runTest {
        val clock = AtomicLong(0L)
        val tracker = tracker(this, throttleMillis = 2_000L, clock = clock)

        tracker.onPageChanged(entry(pageIndex = 1))
        runCurrent()
        clock.set(500L)
        tracker.onPageChanged(entry(pageIndex = 2))
        runCurrent()

        clock.set(2_500L)
        tracker.onPageChanged(entry(pageIndex = 3))
        advanceTimeBy(2_500L)
        runCurrent()

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

    @Test fun `pending positions for different comics survive a flush`() = runTest {
        val tracker = tracker(this)
        tracker.onPageChanged(entry(1))
        tracker.onPageChanged(entry(7).copy(comicKey = ComicKey(SourceId("source-b"), RemoteComicId("comic-1"))))
        tracker.flush()
        assertEquals(setOf(1, 7), progressDao.rows.value.map { it.pageIndex }.toSet())
    }

    @Test fun `the trailing page writes without another turn`() = runTest {
        val tracker = tracker(this)
        tracker.onPageChanged(entry(1))
        runCurrent()
        tracker.onPageChanged(entry(8))
        runCurrent()
        assertEquals(1, progressDao.rows.value.single().pageIndex)
        advanceTimeBy(2000)
        runCurrent()
        assertEquals(8, progressDao.rows.value.single().pageIndex)
    }

    @Test fun `failed storage retains the newest pending position for retry`() = runTest {
        var fail = true
        val unreliable = object : HistoryRepository by repository {
            override suspend fun record(entry: ReadingHistoryEntry) {
                if (fail) throw java.io.IOException("disk full")
                repository.record(entry)
            }
        }
        val tracker = ReadingProgressTracker(unreliable, this, { 0 })
        tracker.onPageChanged(entry(3))
        runCurrent()
        assertEquals(true, tracker.writeFailed.value)
        fail = false
        tracker.flush()
        assertEquals(3, progressDao.rows.value.single().pageIndex)
        assertEquals(false, tracker.writeFailed.value)
    }

    @Test fun `a suspended old write cannot overtake the newer flush`() = runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val written = mutableListOf<Int>()
        val slow = object : HistoryRepository by repository {
            override suspend fun record(entry: ReadingHistoryEntry) {
                if (entry.pageIndex == 1) gate.await()
                written += entry.pageIndex
                repository.record(entry)
            }
        }
        val tracker = ReadingProgressTracker(slow, this, { 0 })
        tracker.onPageChanged(entry(1))
        runCurrent()
        tracker.onPageChanged(entry(9))
        val flush = async { tracker.flush() }
        runCurrent()
        gate.complete(Unit)
        flush.await()
        assertEquals(listOf(1, 9), written)
        assertEquals(9, progressDao.rows.value.single().pageIndex)
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
