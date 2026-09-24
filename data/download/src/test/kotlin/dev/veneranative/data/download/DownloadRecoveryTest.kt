package dev.veneranative.data.download

import dev.veneranative.core.model.ChapterRef
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadRecoveryTest {

    @get:Rule val folder = TemporaryFolder()

    private val chapter = remoteChapter()

    private fun layout(): DownloadFileLayout = DownloadFileLayout(folder.root)

    private fun recovery(
        dao: FakeDownloadDao,
        now: Long = 600_000L,
        staleAfterMillis: Long = HEARTBEAT_STALE_AFTER_MILLIS,
    ): DownloadRecovery = DownloadRecovery(dao = dao, layout = layout(), clock = { now }, staleAfterMillis = staleAfterMillis)

    private suspend fun writeManifest(pages: List<ManifestPage>, contents: String? = null) {
        layout().writeAtomically(
            layout().manifestFile(refSourceOf(chapter), refComicOf(chapter), refChapterOf(chapter)),
            (contents ?: ChapterManifestCodec.encode(manifest(pages))).toByteArray(Charsets.UTF_8),
        )
    }

    private fun manifest(pages: List<ManifestPage>): ChapterManifest = ChapterManifest(
        sourceId = refSourceOf(chapter),
        comicId = refComicOf(chapter),
        chapterId = refChapterOf(chapter),
        title = "Chapter 1",
        comicTitle = "Frieren",
        pages = pages,
    )

    private suspend fun writePage(index: Int, bytes: ByteArray) {
        layout().writeAtomically(
            layout().pageFile(refSourceOf(chapter), refComicOf(chapter), refChapterOf(chapter), index),
            bytes,
        )
    }

    @Test
    fun `pages left running by a worker that stopped reporting go back to the queue`() = runTest {
        val dao = FakeDownloadDao()
        dao.tasks.value = listOf(taskEntity(chapter, workerId = "dead-worker", heartbeatAt = 0L))
        dao.pages.value = listOf(pageEntity(chapter.taskId(), 0, DownloadPageState.Running))

        val report = recovery(dao).recover("live-worker")

        assertEquals(1, report.requeuedZombies)
        assertEquals(DownloadPageState.Queued, dao.pages.value.single().pageState())
        assertEquals("live-worker", dao.tasks.value.single().workerId)
        assertEquals(600_000L, dao.tasks.value.single().heartbeatAt)
    }

    @Test
    fun `this worker's own running pages are left alone`() = runTest {
        val dao = FakeDownloadDao()
        dao.tasks.value = listOf(taskEntity(chapter, workerId = "live-worker", heartbeatAt = 0L))
        dao.pages.value = listOf(pageEntity(chapter.taskId(), 0, DownloadPageState.Running))

        val report = recovery(dao).recover("live-worker")

        assertEquals(0, report.requeuedZombies)
        assertEquals(DownloadPageState.Running, dao.pages.value.single().pageState())
    }

    @Test
    fun `a worker that is still reporting keeps its pages`() = runTest {
        val dao = FakeDownloadDao()
        dao.tasks.value = listOf(taskEntity(chapter, workerId = "other", heartbeatAt = 590_000L))
        dao.pages.value = listOf(pageEntity(chapter.taskId(), 0, DownloadPageState.Running))

        assertEquals(0, recovery(dao, now = 600_000L).recover("live-worker").requeuedZombies)
        assertEquals(DownloadPageState.Running, dao.pages.value.single().pageState())
    }

    @Test
    fun `a chapter nobody claimed yet has nothing to reset`() = runTest {
        val dao = FakeDownloadDao()
        dao.tasks.value = listOf(taskEntity(chapter, workerId = null, heartbeatAt = 0L))
        dao.pages.value = listOf(pageEntity(chapter.taskId(), 0, DownloadPageState.Running))

        assertEquals(0, recovery(dao).recover("live-worker").requeuedZombies)
        assertEquals("live-worker", dao.tasks.value.single().workerId)
    }

    @Test
    fun `a page that claims to be complete but has no file is queued again`() = runTest {
        val dao = FakeDownloadDao()
        dao.tasks.value = listOf(taskEntity(chapter))
        dao.pages.value = listOf(
            pageEntity(chapter.taskId(), 0, DownloadPageState.Succeeded, relativePath = "gone/p00000.bin", bytes = 24),
        )

        val report = recovery(dao).recover("live-worker")

        assertEquals(1, report.repairedFiles)
        assertEquals(DownloadPageState.Queued, dao.pages.value.single().pageState())
        assertEquals(null, dao.pages.value.single().relativePath)
    }

    @Test
    fun `a page whose file is short is queued again`() = runTest {
        writePage(0, pngBytes(8, 12).copyOf(10))
        val dao = FakeDownloadDao()
        dao.tasks.value = listOf(taskEntity(chapter))
        dao.pages.value = listOf(
            pageEntity(
                chapter.taskId(),
                0,
                DownloadPageState.Succeeded,
                relativePath = layout().relativePathOf("manga_dex", "frieren", "ch-1", 0),
                bytes = pngBytes(8, 12).size.toLong(),
            ),
        )

        assertEquals(1, recovery(dao).recover("live-worker").repairedFiles)
    }

    @Test
    fun `a page whose file is intact is left alone`() = runTest {
        val bytes = pngBytes(8, 12)
        writePage(0, bytes)
        val dao = FakeDownloadDao()
        dao.tasks.value = listOf(taskEntity(chapter))
        dao.pages.value = listOf(
            pageEntity(
                chapter.taskId(),
                0,
                DownloadPageState.Succeeded,
                relativePath = layout().relativePathOf("manga_dex", "frieren", "ch-1", 0),
                bytes = bytes.size.toLong(),
            ),
        )

        assertEquals(0, recovery(dao).recover("live-worker").repairedFiles)
        assertEquals(DownloadPageState.Succeeded, dao.pages.value.single().pageState())
    }

    @Test
    fun `a chapter whose rows are gone is rebuilt from its manifest`() = runTest {
        writeManifest(
            listOf(
                ManifestPage(index = 0, imageRef = "https://cdn.test/0.png", fileName = "p00000.bin"),
                ManifestPage(index = 1, imageRef = "https://cdn.test/1.png", fileName = "p00001.bin"),
            ),
        )
        val dao = FakeDownloadDao()

        val report = recovery(dao).recover("live-worker")

        assertEquals(1, report.adoptedTasks)
        assertEquals(2, report.adoptedPages)
        assertEquals(chapter, dao.tasks.value.single().chapterOrNull())
        assertEquals("live-worker", dao.tasks.value.single().workerId)
        assertEquals(listOf(0, 1), dao.pages.value.map { it.pageIndex })
    }

    @Test
    fun `a file that is already on disk is adopted as complete rather than fetched again`() = runTest {
        writePage(0, pngBytes(8, 12))
        writeManifest(listOf(ManifestPage(index = 0, imageRef = "https://cdn.test/0.png", fileName = "p00000.bin")))
        val dao = FakeDownloadDao()

        recovery(dao).recover("live-worker")

        assertEquals(DownloadPageState.Succeeded, dao.pages.value.single().pageState())
        assertEquals(pngBytes(8, 12).size.toLong(), dao.pages.value.single().bytes)
    }

    @Test
    fun `an unreadable manifest is skipped rather than guessed at`() = runTest {
        writeManifest(emptyList(), contents = "{ this is not json")
        val dao = FakeDownloadDao()

        assertEquals(0, recovery(dao).recover("live-worker").adoptedTasks)
        assertTrue(dao.tasks.value.isEmpty())
    }

    @Test
    fun `files no manifest claims are reported and never deleted`() = runTest {
        writeManifest(listOf(ManifestPage(index = 0, imageRef = "https://cdn.test/0.png", fileName = "p00000.bin")))
        writePage(0, pngBytes(8, 12))
        writePage(99, pngBytes(8, 12))
        val dao = FakeDownloadDao()

        val report = recovery(dao).recover("live-worker")

        assertEquals(listOf(layout().relativePathOf("manga_dex", "frieren", "ch-1", 99)), report.orphans)
        assertTrue(layout().pageFile("manga_dex", "frieren", "ch-1", 99).isFile)
    }

    @Test
    fun `a chapter known to the database is not adopted twice`() = runTest {
        writeManifest(listOf(ManifestPage(index = 0, imageRef = "https://cdn.test/0.png", fileName = "p00000.bin")))
        val dao = FakeDownloadDao()
        dao.tasks.value = listOf(taskEntity(chapter))
        dao.pages.value = listOf(pageEntity(chapter.taskId(), 0))

        val report = recovery(dao).recover("live-worker")

        assertEquals(0, report.adoptedTasks)
        assertEquals(0, report.adoptedPages)
        assertEquals(1, dao.pages.value.size)
    }
}
