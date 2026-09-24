package dev.veneranative.data.download.worker

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import android.os.Build
import android.content.pm.ServiceInfo
import dev.veneranative.core.database.VeneraDatabase
import dev.veneranative.core.model.ChapterKey
import dev.veneranative.core.model.ChapterRef
import dev.veneranative.core.model.ComicKey
import dev.veneranative.core.model.RemoteChapterId
import dev.veneranative.core.model.RemoteComicId
import dev.veneranative.core.model.SourceId
import dev.veneranative.core.model.SourcePage
import dev.veneranative.data.download.DownloadEnvironment
import dev.veneranative.data.download.PageByteSource
import dev.veneranative.data.download.PageFetchRequest
import dev.veneranative.data.download.taskId
import java.io.File
import java.io.OutputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The worker against a real WorkManager and a real Room database.
 *
 * These three cases are the ones that decide whether a run is honest: an empty queue must not keep
 * the process awake, a stopped run must not leave files behind, and the foreground promise must name
 * a channel that exists.
 */
@RunWith(AndroidJUnit4::class)
class DownloadWorkerTest {

    private lateinit var context: Context
    private lateinit var database: VeneraDatabase
    private lateinit var root: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        root = File(context.cacheDir, "download-worker-test-${System.nanoTime()}")
        database = Room.inMemoryDatabaseBuilder(context, VeneraDatabase::class.java).build()
        DownloadEnvironment.install(
            DownloadEnvironment(
                filesRoot = root,
                pageSource = StubPageSource(),
                database = { database },
            ),
        )
    }

    @After
    fun tearDown() {
        DownloadEnvironment.uninstall()
        database.close()
        root.deleteRecursively()
    }

    @Test
    fun anEmptyQueueSucceedsWithoutWaiting() = runBlocking {
        val worker = TestListenableWorkerBuilder<DownloadWorker>(context).build()

        val result = worker.doWork()

        assertTrue("an empty queue is a finished run, not a retry", result is ListenableWorker.Result.Success)
    }

    @Test
    fun aStoppedRunWritesNoPages() = runBlocking {
        val repository = DownloadEnvironment.get(context).repository()
        repository.enqueue(
            chapter = chapter(),
            title = "Chapter 1",
            pages = listOf(SourcePage(index = 0, imageRef = "https://example.test/0.png")),
        )

        val worker = TestListenableWorkerBuilder<DownloadWorker>(context).build()
        worker.stop(WorkInfo.STOP_REASON_CANCELLED_BY_APP)
        val result = worker.doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        assertTrue(
            "a stopped run must not leave pages behind",
            DownloadEnvironment.get(context).layout().pageFiles().isEmpty(),
        )
    }

    @Test
    fun theForegroundPromiseUsesARealChannel() = runBlocking {
        val worker = TestListenableWorkerBuilder<DownloadWorker>(context).build()

        val info = worker.getForegroundInfo()

        assertTrue("a foreground run needs a notification id", info.notificationId != 0)
        assertTrue("the notification must use the downloads channel", info.notification.channelId == DownloadNotificationText.CHANNEL_ID)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            assertTrue(
                "API 34+ downloads must declare the dataSync foreground type",
                info.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC != 0,
            )
        }
    }

    @Test
    fun aQueuedPageIsFetchedValidatedAndMarkedComplete() = runBlocking {
        val repository = DownloadEnvironment.get(context).repository()
        repository.enqueue(
            chapter = chapter(),
            title = "Chapter 1",
            pages = listOf(SourcePage(index = 0, imageRef = "https://example.test/0.png")),
        )

        val worker = TestListenableWorkerBuilder<DownloadWorker>(context).build()
        val result = worker.doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        val page = repository.pagesOf(chapter()).single()
        assertTrue("downloaded page must reach the completed state", page.state.name == "Succeeded")
        assertTrue("completed page must have an intact file", DownloadEnvironment.get(context).layout().absoluteOf(page.relativePath!!).isFile)
    }

    @Test
    fun anUnexpectedPageExceptionIsRecordedAsFailed() = runBlocking {
        DownloadEnvironment.install(
            DownloadEnvironment(
                filesRoot = root,
                pageSource = object : PageByteSource {
                    override suspend fun fetch(request: PageFetchRequest, sink: OutputStream): Long? {
                        throw IllegalStateException("broken page source")
                    }
                },
                database = { database },
            ),
        )
        val repository = DownloadEnvironment.get(context).repository()
        repository.enqueue(
            chapter = chapter(),
            title = "Chapter 1",
            pages = listOf(SourcePage(index = 0, imageRef = "https://example.test/0.png")),
        )

        val result = TestListenableWorkerBuilder<DownloadWorker>(context).build().doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        assertTrue(repository.pagesOf(chapter()).single().state.name == "Failed")
    }

    @Test
    fun aFreshPageOwnedByAnotherWorkerKeepsTheWorkPending() = runBlocking {
        val repository = DownloadEnvironment.get(context).repository()
        repository.enqueue(
            chapter = chapter(),
            title = "Chapter 1",
            pages = listOf(SourcePage(index = 0, imageRef = "https://example.test/0.png")),
        )
        repository.markRunning(chapter(), 0)
        val dao = database.downloadDao()
        val task = dao.task(chapter().taskId())!!
        dao.upsertTask(task.copy(workerId = "previous-worker", heartbeatAt = System.currentTimeMillis()))

        val result = TestListenableWorkerBuilder<DownloadWorker>(context).build().doWork()

        assertTrue(result is ListenableWorker.Result.Retry)
    }

    private fun chapter(): ChapterRef = ChapterRef.Remote(
        ChapterKey(
            comicKey = ComicKey(SourceId("demo"), RemoteComicId("comic-1")),
            remoteId = RemoteChapterId("chapter-1"),
        ),
    )

    /** Produces no bytes: these cases are about what the worker does, not about a source. */
    private class StubPageSource : PageByteSource {

        override suspend fun fetch(request: PageFetchRequest, sink: OutputStream): Long {
            val bytes = pngBytes()
            sink.write(bytes)
            return bytes.size.toLong()
        }

        private fun pngBytes(): ByteArray = byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
            0, 0, 0, 13, 0x49, 0x48, 0x44, 0x52,
            0, 0, 0, 8, 0, 0, 0, 12,
        )
    }
}
