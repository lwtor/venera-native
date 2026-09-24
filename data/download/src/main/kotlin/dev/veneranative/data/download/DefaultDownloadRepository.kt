package dev.veneranative.data.download

import dev.veneranative.core.database.DownloadDao
import dev.veneranative.core.database.DownloadPageEntity
import dev.veneranative.core.model.ChapterRef
import dev.veneranative.core.model.SourcePage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * The download queue over Room.
 *
 * Every operation that touches rows runs on [io], and every one of them recomputes the chapter's
 * progress from its pages afterwards. That is the whole reason a chapter's state is derived rather
 * than stored: a row that says `Completed` next to a page that says `Failed` is a contradiction the
 * UI would have to resolve, and there is no way to resolve it correctly.
 *
 * The chapter directory is the other half of the truth. `chapter.json` is written whenever a chapter
 * is enqueued, because the rows can be lost to a restore while the files are what the user actually
 * has, and recovery rebuilds the first from the second.
 */
class DefaultDownloadRepository(
    private val dao: DownloadDao,
    private val layout: DownloadFileLayout,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : DownloadRepository {

    private val recovery = DownloadRecovery(dao = dao, layout = layout, clock = clock)

    override fun observeTasks(): Flow<List<DownloadTask>> =
        dao.observeTasks().map { rows -> rows.mapNotNull { it.toDomain() } }

    override fun observeTask(chapter: ChapterRef): Flow<DownloadTask?> =
        dao.observeTask(chapter.taskId()).map { it?.toDomain() }

    override suspend fun enqueue(
        chapter: ChapterRef,
        title: String,
        pages: List<SourcePage>,
        comicTitle: String?,
    ) = withContext(io) {
        val now = clock()
        val plan = DownloadPlanner.plan(chapter, title, comicTitle, pages, now)
        val existing = dao.task(plan.task.taskId)

        if (existing == null) {
            dao.insertTask(plan.task)
        } else {
            // Re-enqueueing replaces the description, never the progress: a chapter the user already
            // downloaded must not start over because they pressed the button twice.
            dao.upsertTask(
                existing.copy(
                    title = plan.task.title,
                    comicTitle = plan.task.comicTitle,
                    pageCount = plan.task.pageCount,
                    updatedAt = now,
                ),
            )
        }

        val known = dao.pages(plan.task.taskId).map { it.pageIndex }.toSet()
        val missing = plan.pages.filter { it.pageIndex !in known }
        if (missing.isNotEmpty()) dao.upsertPages(missing)

        writeManifest(chapter, title, comicTitle, plan.pages)
        refreshProgress(plan.task.taskId)
    }

    override suspend fun pause(chapter: ChapterRef) = withContext(io) {
        // Only pages that have not started are paused; one already in flight is allowed to land,
        // because abandoning it mid-write is how a directory fills with half-written files.
        dao.pauseQueuedPages(chapter.taskId())
        refreshProgress(chapter.taskId())
    }

    override suspend fun resume(chapter: ChapterRef) = withContext(io) {
        dao.requeuePausedPages(chapter.taskId())
        refreshProgress(chapter.taskId())
    }

    override suspend fun cancel(chapter: ChapterRef) = withContext(io) {
        val taskId = chapter.taskId()
        // Files first: the promise the repository makes is that cancelling leaves nothing behind,
        // and a row pointing at a deleted file is a lie the next recovery pass repairs anyway.
        layout.deleteChapterFiles(refSourceOf(chapter), refComicOf(chapter), refChapterOf(chapter))
        if (dao.task(taskId) != null) dao.deleteTask(taskId)
    }

    override suspend fun retryFailed(chapter: ChapterRef) = withContext(io) {
        dao.requeueFailedPages(chapter.taskId())
        refreshProgress(chapter.taskId())
    }

    override suspend fun recover(workerId: String): RecoveryReport = withContext(io) {
        val report = recovery.recover(workerId)
        for (task in dao.tasksOf(workerId)) refreshProgress(task.taskId)
        report
    }

    override suspend fun isCompleteOffline(chapter: ChapterRef): Boolean = withContext(io) {
        val rows = dao.pages(chapter.taskId())
        rows.isNotEmpty() && rows.all { row -> row.isIntactOnDisk() }
    }

    override suspend fun pagesOf(chapter: ChapterRef): List<DownloadPage> = withContext(io) {
        dao.pages(chapter.taskId()).map { row -> row.toDomain(chapter) }
    }

    override suspend fun queuedPages(limit: Int): List<DownloadPage> = withContext(io) {
        // Two queries rather than one per page: the order comes from the task rows, so they are read
        // once and the pages are matched against them.
        val tasks = dao.tasks().associateBy { it.taskId }
        dao.queuedPages(limit).mapNotNull { row ->
            tasks[row.taskId]?.chapterOrNull()?.let { chapter -> row.toDomain(chapter) }
        }
    }

    override suspend fun markRunning(chapter: ChapterRef, index: Int): Boolean =
        movePage(chapter, index, DownloadPageState.Running) { row, _ ->
            row.copy(attempts = row.attempts + 1)
        }

    override suspend fun markPaused(chapter: ChapterRef, index: Int): Boolean =
        movePage(chapter, index, DownloadPageState.Paused) { row, _ -> row }

    override suspend fun markSucceeded(
        chapter: ChapterRef,
        index: Int,
        relativePath: String,
        bytes: Long,
    ): Boolean = movePage(chapter, index, DownloadPageState.Succeeded) { row, _ ->
        row.copy(relativePath = relativePath, bytes = bytes, lastError = null)
    }

    override suspend fun markFailed(chapter: ChapterRef, index: Int, error: DownloadError): Boolean =
        movePage(chapter, index, DownloadPageState.Failed) { row, _ ->
            row.copy(relativePath = null, bytes = 0L, lastError = error.toColumn())
        }

    /**
     * Applies one page transition, or refuses it.
     *
     * Refusing is the point: a worker that lost a race must not be able to mark a cancelled page
     * succeeded, and a page that is already on disk must not be sent back to the queue by a late
     * callback from an attempt that was already superseded.
     */
    private suspend fun movePage(
        chapter: ChapterRef,
        index: Int,
        to: DownloadPageState,
        change: (DownloadPageEntity, DownloadPageState) -> DownloadPageEntity,
    ): Boolean = withContext(io) {
        val taskId = chapter.taskId()
        val row = dao.page(taskId, index) ?: return@withContext false
        val from = DownloadStateMachine.stateNamed(row.state) ?: return@withContext false
        if (!DownloadStateMachine.canMove(from, to)) return@withContext false

        val next = change(row, from)
        val changed = dao.updatePage(
            taskId = taskId,
            pageIndex = index,
            expectedState = from.name,
            state = to.name,
            relativePath = next.relativePath,
            bytes = next.bytes,
            attempts = next.attempts,
            lastError = next.lastError,
        )
        if (changed == 0) return@withContext false
        refreshProgress(taskId)
        true
    }

    /** A chapter's headline state and count, recomputed from the pages that are actually there. */
    private suspend fun refreshProgress(taskId: String) {
        val rows = dao.pages(taskId)
        val states = rows.map { it.pageState() }
        dao.updateTaskProgress(
            taskId = taskId,
            state = DownloadStateMachine.chapterStateOf(states).name,
            completedPages = states.count { it == DownloadPageState.Succeeded },
            updatedAt = clock(),
        )
    }

    private suspend fun writeManifest(
        chapter: ChapterRef,
        title: String,
        comicTitle: String?,
        pages: List<DownloadPageEntity>,
    ) {
        val manifest = ChapterManifest(
            sourceId = refSourceOf(chapter),
            comicId = refComicOf(chapter),
            chapterId = refChapterOf(chapter),
            title = title,
            comicTitle = comicTitle,
            pages = pages.map { row ->
                ManifestPage(
                    index = row.pageIndex,
                    imageRef = row.imageRef,
                    fileName = layout.pageFileName(row.pageIndex),
                )
            },
        )
        layout.writeAtomically(
            layout.manifestFile(refSourceOf(chapter), refComicOf(chapter), refChapterOf(chapter)),
            ChapterManifestCodec.encode(manifest).toByteArray(Charsets.UTF_8),
        )
    }

    /** A page that is only claimed to be complete is not complete: the file has to still be there. */
    private fun DownloadPageEntity.isIntactOnDisk(): Boolean {
        if (pageState() != DownloadPageState.Succeeded) return false
        val path = relativePath ?: return false
        if (bytes <= 0L) return false
        val file = layout.absoluteOf(path)
        return file.isFile && file.length() == bytes
    }
}
