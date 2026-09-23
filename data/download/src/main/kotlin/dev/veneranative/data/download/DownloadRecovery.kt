package dev.veneranative.data.download

import dev.veneranative.core.database.DownloadDao
import dev.veneranative.core.database.DownloadPageEntity
import dev.veneranative.core.database.DownloadTaskEntity
import java.io.File

/** What one recovery pass found and did. */
data class RecoveryReport(
    val workerId: String,
    /** Pages a worker that stopped reporting had left running; they are queued again. */
    val requeuedZombies: Int,
    /** Pages marked complete whose file is missing or the wrong size; they are queued again. */
    val repairedFiles: Int,
    val adoptedTasks: Int,
    val adoptedPages: Int,
    /** Files on disk no chapter claims. Reported, never deleted: they may be a chapter the user still wants. */
    val orphans: List<String>,
)

/**
 * How long a worker may go without reporting before its pages are considered abandoned.
 *
 * A worker only reports while it is looping, so a process the system killed leaves its `Running`
 * pages behind forever. Five minutes is long enough that a slow page or a brief doze is never
 * mistaken for a death, and short enough that a killed run does not block a chapter for hours.
 */
const val HEARTBEAT_STALE_AFTER_MILLIS: Long = 5 * 60 * 1000L

/** Why a page that said it was complete is being fetched again: the file is gone or short. */
private val FILE_MISSING_ERROR: DownloadError = DownloadError.Corrupt("the downloaded file is gone")

/**
 * Puts the queue back in a state that matches the disk after the process was killed.
 *
 * A killed process leaves three kinds of lie behind: a page marked running that nobody is running, a
 * page marked complete whose file was never finished, and — when the database itself is gone, after a
 * restore — files on disk with no rows describing them. Each is repaired from a different source of
 * truth: the heartbeat for the first, the file for the second, `chapter.json` for the third.
 */
class DownloadRecovery(
    private val dao: DownloadDao,
    private val layout: DownloadFileLayout,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val staleAfterMillis: Long = HEARTBEAT_STALE_AFTER_MILLIS,
) {

    suspend fun recover(workerId: String): RecoveryReport {
        val now = clock()
        val staleBefore = now - staleAfterMillis

        dao.claimTasks(workerId, now)
        val zombies = dao.countZombiePages(workerId, staleBefore)
        dao.resetZombiePages(workerId, staleBefore)

        val repaired = verifyFiles()
        val adoption = adoptFromManifests(now)
        val orphans = findOrphans()

        return RecoveryReport(
            workerId = workerId,
            requeuedZombies = zombies,
            repairedFiles = repaired,
            adoptedTasks = adoption.first,
            adoptedPages = adoption.second,
            orphans = orphans,
        )
    }

    /**
     * A page that says it is complete must have a complete file.
     *
     * The row was written before the rename in a crash window, or the file was deleted by something
     * outside the app; either way the promise is false and the page has to be fetched again.
     */
    private suspend fun verifyFiles(): Int {
        var repaired = 0
        for (page in dao.succeededPages()) {
            val path = page.relativePath ?: continue
            val file = layout.absoluteOf(path)
            if (!file.isFile || file.length() != page.bytes || page.bytes == 0L) {
                dao.requeuePage(page.taskId, page.pageIndex, FILE_MISSING_ERROR.toColumn())
                repaired++
            }
        }
        return repaired
    }

    /** Rebuilds rows for chapters whose files exist but whose rows do not. */
    private suspend fun adoptFromManifests(now: Long): Pair<Int, Int> {
        var adoptedTasks = 0
        var adoptedPages = 0
        for (file in layout.manifestFiles()) {
            val manifest = ChapterManifestCodec.decode(file.readText()) ?: continue
            val chapter = chapterRefOf(manifest.sourceId, manifest.comicId, manifest.chapterId)
                ?: continue
            val taskId = chapter.taskId()
            if (dao.task(taskId) == null) {
                dao.insertTask(
                    DownloadTaskEntity(
                        taskId = taskId,
                        refSource = manifest.sourceId,
                        refComic = manifest.comicId,
                        refChapter = manifest.chapterId,
                        title = manifest.title,
                        comicTitle = manifest.comicTitle,
                        pageCount = manifest.pages.size,
                        completedPages = 0,
                        state = DownloadChapterState.Queued.name,
                        workerId = null,
                        heartbeatAt = 0L,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
                adoptedTasks++
            }
            val known = dao.pages(taskId).map { it.pageIndex }.toSet()
            val missing = manifest.pages
                .filter { it.index !in known }
                .map { page -> rowFor(taskId, manifest, page) }
            if (missing.isNotEmpty()) {
                dao.upsertPages(missing)
                adoptedPages += missing.size
            }
        }
        return adoptedTasks to adoptedPages
    }

    private fun rowFor(
        taskId: String,
        manifest: ChapterManifest,
        page: ManifestPage,
    ): DownloadPageEntity {
        val relativePath = layout.relativePathOf(
            sourceId = manifest.sourceId,
            comicId = manifest.comicId,
            chapterId = manifest.chapterId,
            index = page.index,
        )
        val file = layout.absoluteOf(relativePath)
        // A file that is already there counts as done: adopting it as queued would download it again.
        val complete = file.isFile && file.length() > 0L
        return DownloadPageEntity(
            taskId = taskId,
            pageIndex = page.index,
            imageRef = page.imageRef,
            state = if (complete) DownloadPageState.Succeeded.name else DownloadPageState.Queued.name,
            relativePath = if (complete) relativePath else null,
            bytes = if (complete) file.length() else 0L,
            attempts = 0,
            lastError = null,
        )
    }

    /** Page files that no chapter's manifest mentions. */
    private fun findOrphans(): List<String> {
        val claimed = layout.manifestFiles().mapNotNull { file ->
            ChapterManifestCodec.decode(file.readText())?.let { manifest ->
                Triple(manifest.sourceId, manifest.comicId, manifest.chapterId) to
                    manifest.pages.map { it.fileName }.toSet()
            }
        }.toMap()

        return layout.pageFiles()
            .filter { file -> file.name !in claimed[ownerOf(file)].orEmpty() }
            .map { file -> layout.relativeOf(file) }
            .sorted()
    }

    /** Which chapter a page file sits under, by walking back to its `pages` directory's chapter. */
    private fun ownerOf(file: File): Triple<String, String, String>? {
        val manifest = File(file.parentFile?.parentFile, "chapter.json")
        val text = manifest.takeIf { it.isFile }?.readText() ?: return null
        val decoded = ChapterManifestCodec.decode(text) ?: return null
        return Triple(decoded.sourceId, decoded.comicId, decoded.chapterId)
    }
}
