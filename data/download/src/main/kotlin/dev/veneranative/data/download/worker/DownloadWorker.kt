package dev.veneranative.data.download.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.ForegroundInfo
import dev.veneranative.data.download.DownloadChapterState
import dev.veneranative.data.download.DownloadEnvironment
import dev.veneranative.data.download.DownloadError
import dev.veneranative.data.download.DownloadPage
import dev.veneranative.data.download.DownloadRepository
import dev.veneranative.data.download.DownloadStateMachine
import dev.veneranative.data.download.DownloadTarget
import dev.veneranative.data.download.PageDownloadResult
import dev.veneranative.data.download.PageDownloader
import dev.veneranative.data.download.QueuePage
import dev.veneranative.data.download.refChapterOf
import dev.veneranative.data.download.refComicOf
import dev.veneranative.data.download.refSourceOf
import dev.veneranative.data.download.taskId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Drains the download queue until it is empty or WorkManager stops this run.
 *
 * The worker owns no queue state: it claims pages, fetches them, and records what happened. Room is
 * the queue, so a run that dies mid-page loses nothing but the page it was on, and the next run's
 * `recover()` puts that page back.
 *
 * It also owns no concurrency: [dev.veneranative.data.download.DownloadQueue] decides how many pages
 * are in flight, so replacing this worker with another executor cannot change how hard a source is
 * hit.
 */
class DownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val environment = DownloadEnvironment.get(applicationContext)
        val repository = environment.repository()
        val queue = environment.queue()
        val downloader = environment.pageDownloader()
        val notifier = DownloadNotifier(applicationContext)
        val workerId = id.toString()

        // A previous run may have been killed; its pages are put back before this run claims any.
        repository.recover(workerId)
        environment.heartbeat(workerId)
        setForeground(notifier.foregroundInfo(progressOf(repository)))

        while (!isStopped) {
            environment.heartbeat(workerId)
            val pages = repository.queuedPages(BATCH_SIZE)
            if (pages.isEmpty()) {
                // A previous worker may still own a fresh Running page. Keep the WorkSpec alive
                // until its heartbeat is stale, then the next attempt's recovery can reclaim it.
                if (repository.observeTasks().first().any { it.state == DownloadChapterState.Running }) {
                    return Result.retry()
                }
                break
            }

            // Claiming is what turns "this page is waiting" into "this worker is doing it"; a page
            // that cannot be claimed was paused or cancelled underneath us and must not be fetched.
            val claimed = pages.filter { repository.markRunning(it.chapter, it.index) }
            if (claimed.isEmpty()) break

            val byKey = claimed.associateBy { page -> keyOf(page) }
            try {
                val results = queue.run(claimed.map { page -> page.toQueuePage() }) { target ->
                    val page = byKey[keyOf(target)] ?: return@run null
                    fetchPage(repository, downloader, page)
                }
                // fetchPage records expected failures itself. The queue also converts unexpected
                // exceptions into errors; those leave the page Running unless settled here.
                results.forEach { result ->
                    val error = result.error ?: return@forEach
                    val page = byKey[keyOf(result.page)] ?: return@forEach
                    repository.markFailed(page.chapter, page.index, error)
                }
            } finally {
                // An explicit stop is an interruption, not a failed download. Restore every page
                // claimed by this batch but not already completed while cancellation is suppressed.
                if (isStopped) withContext(NonCancellable) {
                    claimed.forEach { page -> repository.markPaused(page.chapter, page.index) }
                }
            }

            setForeground(notifier.foregroundInfo(progressOf(repository)))
        }

        // Success even when pages failed or the run was stopped: failing would make WorkManager
        // retry on its back-off schedule, which is not what "paused" or "some pages failed" means.
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        DownloadNotifier(applicationContext).foregroundInfo(DownloadProgress())

    /**
     * Fetches one page, retrying inside the run while the state machine permits it.
     *
     * [DownloadPage.attempts] already counts this attempt — `markRunning` incremented the row — so a
     * page that has failed twice before gets one try here, not three. A page that fails for good is
     * recorded as failed rather than abandoned: the chapter stays partially readable and the UI can
     * offer a retry, which is more useful than a row that pretends nothing happened.
     */
    private suspend fun fetchPage(
        repository: DownloadRepository,
        downloader: PageDownloader,
        page: DownloadPage,
    ): DownloadError? {
        val target = DownloadTarget(
            sourceId = refSourceOf(page.chapter),
            comicId = refComicOf(page.chapter),
            chapterId = refChapterOf(page.chapter),
            index = page.index,
            imageRef = page.imageRef,
        )
        var attempts = page.attempts + 1
        var lastError: DownloadError = DownloadError.NotResolvable
        while (true) {
            when (val result = downloader.download(target)) {
                is PageDownloadResult.Succeeded -> {
                    repository.markSucceeded(page.chapter, page.index, result.relativePath, result.bytes)
                    return null
                }

                is PageDownloadResult.Failed -> {
                    lastError = result.error
                    if (isStopped || !DownloadStateMachine.shouldRetry(attempts)) break
                    attempts++
                }
            }
        }
        repository.markFailed(page.chapter, page.index, lastError)
        return lastError
    }

    /** Reports the chapter the run is actually on, not whichever one happens to be first. */
    private suspend fun progressOf(repository: DownloadRepository): DownloadProgress {
        val tasks = repository.observeTasks().first()
        val current = tasks.firstOrNull { it.state in IN_FLIGHT } ?: tasks.firstOrNull()
            ?: return DownloadProgress()
        return DownloadProgress(
            chapterTitle = current.title,
            completedPages = current.completedPages,
            totalPages = current.pageCount,
        )
    }

    private fun DownloadPage.toQueuePage(): QueuePage = QueuePage(
        taskId = chapter.taskId(),
        pageIndex = index,
        sourceId = refSourceOf(chapter),
        imageRef = imageRef,
    )

    private fun keyOf(page: DownloadPage): String = "${page.chapter.taskId()}:${page.index}"

    private fun keyOf(page: QueuePage): String = "${page.taskId}:${page.pageIndex}"

    private companion object {

        /**
         * How many pages are taken from Room at a time. The queue still runs them under its own
         * limits, so this only decides how often the worker goes back to the database and updates
         * the notification.
         */
        const val BATCH_SIZE: Int = 8

        val IN_FLIGHT: Set<DownloadChapterState> = setOf(
            DownloadChapterState.Running,
            DownloadChapterState.Queued,
            DownloadChapterState.Partial,
        )
    }
}
