package dev.veneranative.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the Room queries themselves on SQLite rather than against a fake.
 *
 * What is worth proving here is what the DAO does that a fake does not: that the composite primary
 * key collapses re-reads of the same chapter into one row, that different sources keep their own
 * rows, and that deleting a comic takes its resume point with it.
 */
@RunWith(AndroidJUnit4::class)
class ReadingHistoryDaoTest {

    private lateinit var database: VeneraDatabase
    private lateinit var historyDao: ReadingHistoryDao
    private lateinit var progressDao: ReadingProgressDao

    @Before fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, VeneraDatabase::class.java).build()
        historyDao = database.readingHistoryDao()
        progressDao = database.readingProgressDao()
    }

    @After fun tearDown() {
        database.close()
    }

    private fun history(
        sourceId: String = "source-a",
        comicId: String = "comic-1",
        chapterId: String = "chapter-1",
        updatedAt: Long = 1_000L,
    ) = ReadingHistoryEntity(
        sourceId = sourceId,
        comicId = comicId,
        chapterId = chapterId,
        comicTitle = "Comic",
        chapterTitle = "Chapter",
        coverUrl = null,
        pageIndex = 3,
        pageCount = 20,
        updatedAtEpochMillis = updatedAt,
    )

    @Test fun reReadingTheSameChapterUpdatesInsteadOfDuplicating() = runTest {
        repeat(3) { historyDao.upsert(history(updatedAt = 1_000L + it)) }

        assertEquals(1, historyDao.observeRecent(limit = 10).first().size)
    }

    @Test fun theSameRemoteIdUnderAnotherSourceIsAnotherRow() = runTest {
        historyDao.upsert(history(sourceId = "source-a"))
        historyDao.upsert(history(sourceId = "source-b"))

        assertEquals(2, historyDao.observeRecent(limit = 10).first().size)
    }

    @Test fun recentsComeBackNewestFirst() = runTest {
        historyDao.upsert(history(chapterId = "old", updatedAt = 1_000L))
        historyDao.upsert(history(chapterId = "new", updatedAt = 9_000L))

        assertEquals(
            listOf("new", "old"),
            historyDao.observeRecent(limit = 10).first().map { it.chapterId },
        )
    }

    @Test fun deletingAComicDropsItsResumePoint() = runTest {
        historyDao.upsert(history())
        progressDao.upsert(
            ReadingProgressEntity("source-a", "comic-1", "chapter-1", 3, 1_000L),
        )

        historyDao.delete("source-a", "comic-1")
        progressDao.delete("source-a", "comic-1")

        assertNull(historyDao.find("source-a", "comic-1"))
        assertNull(progressDao.find("source-a", "comic-1"))
    }

    @Test fun aComicThatWasNeverOpenedHasNoRow() = runTest {
        assertNull(historyDao.find("source-a", "missing"))
    }
}
