package dev.veneranative.data.download

import dev.veneranative.core.model.ChapterRef
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultDownloadRepositoryTest {

    @get:Rule val folder = TemporaryFolder()

    private val chapter = remoteChapter()
    private val later = remoteChapter(chapter = "ch-2")

    private fun layout(): DownloadFileLayout = DownloadFileLayout(folder.root)

    /** Runs on the test scheduler, so a repository call never leaves the test's own thread. */
    private fun TestScope.repository(dao: FakeDownloadDao, now: Long = 1_000L): DefaultDownloadRepository =
        DefaultDownloadRepository(
            dao = dao,
            layout = layout(),
            clock = { now },
            io = UnconfinedTestDispatcher(testScheduler),
        )

    /** Marks a page running, writes a real image for it and marks it succeeded. */
    private suspend fun complete(repo: DefaultDownloadRepository, chapter: ChapterRef, index: Int) {
        val path = layout().relativePathOf(
            sourceId = refSourceOf(chapter),
            comicId = refComicOf(chapter),
            chapterId = refChapterOf(chapter),
            index = index,
        )
        val bytes = pngBytes(8, 12)
        assertTrue(repo.markRunning(chapter, index))
        layout().writeAtomically(layout().absoluteOf(path), bytes)
        assertTrue(repo.markSucceeded(chapter, index, path, bytes.size.toLong()))
    }

    private fun manifestOf(chapter: ChapterRef): ChapterManifest? =
        ChapterManifestCodec.decode(
            layout().manifestFile(refSourceOf(chapter), refComicOf(chapter), refChapterOf(chapter)).readText(),
        )

    @Test
    fun `enqueueing a chapter creates one row per page and a manifest`() = runTest {
        val (repo, dao) = FakeDownloadDao().let { repository(it) to it }

        repo.enqueue(chapter, "Chapter 1", sourcePages(3), comicTitle = "Frieren")

        assertEquals(1, dao.tasks.value.size)
        assertEquals(listOf(0, 1, 2), dao.pages.value.map { it.pageIndex })
        assertEquals(3, dao.tasks.value.single().pageCount)
        assertEquals(DownloadChapterState.Queued, dao.tasks.value.single().toDomain()!!.state)
        assertEquals(3, manifestOf(chapter)!!.pages.size)
        assertEquals("Frieren", manifestOf(chapter)!!.comicTitle)
    }

    @Test
    fun `enqueueing the same chapter twice does not duplicate pages`() = runTest {
        val (repo, dao) = FakeDownloadDao().let { repository(it) to it }

        repo.enqueue(chapter, "Chapter 1", sourcePages(3))
        repo.enqueue(chapter, "Chapter 1", sourcePages(3))

        assertEquals(1, dao.tasks.value.size)
        assertEquals(3, dao.pages.value.size)
    }

    @Test
    fun `enqueueing again keeps the progress already made`() = runTest {
        val (repo, dao) = FakeDownloadDao().let { repository(it) to it }
        repo.enqueue(chapter, "Chapter 1", sourcePages(3))
        complete(repo, chapter, 0)

        repo.enqueue(chapter, "Chapter 1", sourcePages(3))

        assertEquals(1, dao.tasks.value.single().completedPages)
        assertEquals(DownloadPageState.Succeeded, dao.pages.value.first { it.pageIndex == 0 }.pageState())
    }

    @Test
    fun `a chapter is completed once every page has landed`() = runTest {
        val (repo, dao) = FakeDownloadDao().let { repository(it) to it }
        repo.enqueue(chapter, "Chapter 1", sourcePages(2))

        complete(repo, chapter, 0)
        assertEquals(DownloadChapterState.Queued, dao.tasks.value.single().toDomain()!!.state)

        repo.markRunning(chapter, 1)
        assertEquals(DownloadChapterState.Running, dao.tasks.value.single().toDomain()!!.state)

        val path = layout().relativePathOf("manga_dex", "frieren", "ch-1", 1)
        layout().writeAtomically(layout().absoluteOf(path), pngBytes(8, 12))
        assertTrue(repo.markSucceeded(chapter, 1, path, pngBytes(8, 12).size.toLong()))

        assertEquals(DownloadChapterState.Completed, dao.tasks.value.single().toDomain()!!.state)
        assertEquals(2, dao.tasks.value.single().completedPages)
    }

    @Test
    fun `pausing stops only the pages that have not started`() = runTest {
        val (repo, dao) = FakeDownloadDao().let { repository(it) to it }
        repo.enqueue(chapter, "Chapter 1", sourcePages(3))
        repo.markRunning(chapter, 0)

        repo.pause(chapter)

        assertEquals(DownloadPageState.Running, dao.pages.value[0].pageState())
        assertEquals(DownloadPageState.Paused, dao.pages.value[1].pageState())
        assertEquals(DownloadPageState.Paused, dao.pages.value[2].pageState())
        assertEquals(DownloadChapterState.Paused, dao.tasks.value.single().toDomain()!!.state)
    }

    @Test
    fun `resuming puts exactly the paused pages back`() = runTest {
        val (repo, dao) = FakeDownloadDao().let { repository(it) to it }
        repo.enqueue(chapter, "Chapter 1", sourcePages(2))
        repo.pause(chapter)

        repo.resume(chapter)

        assertEquals(listOf(DownloadPageState.Queued, DownloadPageState.Queued), dao.pages.value.map { it.pageState() })
    }

    @Test
    fun `a failed page comes back on retry`() = runTest {
        val (repo, dao) = FakeDownloadDao().let { repository(it) to it }
        repo.enqueue(chapter, "Chapter 1", sourcePages(2))
        repo.markRunning(chapter, 0)
        repo.markFailed(chapter, 0, DownloadError.Network)

        assertEquals(DownloadChapterState.Partial, dao.tasks.value.single().toDomain()!!.state)

        repo.retryFailed(chapter)

        assertEquals(DownloadPageState.Queued, dao.pages.value[0].pageState())
        assertEquals(0, dao.pages.value[0].attempts)
        assertEquals(null, dao.pages.value[0].lastError)
    }

    @Test
    fun `a page cannot jump from queued straight to succeeded`() = runTest {
        val (repo, dao) = FakeDownloadDao().let { repository(it) to it }
        repo.enqueue(chapter, "Chapter 1", sourcePages(1))

        assertFalse(repo.markSucceeded(chapter, 0, "anywhere", 10L))
        assertEquals(DownloadPageState.Queued, dao.pages.value.single().pageState())
    }

    @Test
    fun `a cancelled page cannot be started again`() = runTest {
        val dao = FakeDownloadDao()
        dao.tasks.value = listOf(taskEntity(chapter))
        dao.pages.value = listOf(pageEntity(chapter.taskId(), 0, DownloadPageState.Canceled))
        val repo = repository(dao)

        assertFalse(repo.markRunning(chapter, 0))
        assertEquals(DownloadPageState.Canceled, dao.pages.value.single().pageState())
    }

    @Test
    fun `cancelling deletes the rows and leaves no files behind`() = runTest {
        val (repo, dao) = FakeDownloadDao().let { repository(it) to it }
        repo.enqueue(chapter, "Chapter 1", sourcePages(2))
        complete(repo, chapter, 0)

        repo.cancel(chapter)

        assertTrue(dao.tasks.value.isEmpty())
        assertTrue(dao.pages.value.isEmpty())
        assertEquals(emptyList<String>(), layout().pageFiles().map { it.name })
        assertFalse(layout().manifestFile("manga_dex", "frieren", "ch-1").exists())
    }

    @Test
    fun `a chapter is offline readable only while every file is intact`() = runTest {
        val (repo, dao) = FakeDownloadDao().let { repository(it) to it }
        repo.enqueue(chapter, "Chapter 1", sourcePages(2))
        complete(repo, chapter, 0)

        assertFalse(repo.isCompleteOffline(chapter))

        complete(repo, chapter, 1)
        assertTrue(repo.isCompleteOffline(chapter))

        layout().pageFile("manga_dex", "frieren", "ch-1", 0).delete()
        assertFalse(repo.isCompleteOffline(chapter))
    }

    @Test
    fun `an unknown chapter is never offline readable`() = runTest {
        val (repo, _) = FakeDownloadDao().let { repository(it) to it }

        assertFalse(repo.isCompleteOffline(remoteChapter(chapter = "never-downloaded")))
    }

    @Test
    fun `queued pages come oldest chapter first and lowest page first`() = runTest {
        val dao = FakeDownloadDao()
        dao.tasks.value = listOf(
            taskEntity(chapter, createdAt = 2_000L),
            taskEntity(later, createdAt = 1_000L),
        )
        dao.pages.value = listOf(
            pageEntity(chapter.taskId(), 1),
            pageEntity(chapter.taskId(), 0),
            pageEntity(later.taskId(), 2),
            pageEntity(later.taskId(), 0),
        )
        val repo = repository(dao)

        assertEquals(
            listOf("ch-2#0", "ch-2#2", "ch-1#0", "ch-1#1"),
            repo.queuedPages(10).map { "${refChapterOf(it.chapter)}#${it.index}" },
        )
    }

    @Test
    fun `pages come back with the chapter they belong to`() = runTest {
        val (repo, _) = FakeDownloadDao().let { repository(it) to it }
        repo.enqueue(chapter, "Chapter 1", sourcePages(2))

        assertEquals(listOf(chapter, chapter), repo.pagesOf(chapter).map { it.chapter })
        assertEquals(listOf(0, 1), repo.pagesOf(chapter).map { it.index })
    }

    @Test
    fun `recovery claims the queue for this worker and reports what it repaired`() = runTest {
        val dao = FakeDownloadDao()
        dao.tasks.value = listOf(taskEntity(chapter, workerId = "dead", heartbeatAt = 0L))
        dao.pages.value = listOf(pageEntity(chapter.taskId(), 0, DownloadPageState.Running))
        val repo = repository(dao, now = 600_000L)

        val report = repo.recover("live")

        assertEquals(1, report.requeuedZombies)
        assertEquals(DownloadPageState.Queued, dao.pages.value.single().pageState())
    }
}
