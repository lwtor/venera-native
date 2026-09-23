package dev.veneranative.data.download

import dev.veneranative.core.database.DownloadPageEntity
import dev.veneranative.core.database.DownloadTaskEntity
import dev.veneranative.core.model.ChapterRef
import dev.veneranative.core.model.SourcePage

/** A chapter's rows, before they are written. */
data class DownloadPlan(
    val task: DownloadTaskEntity,
    val pages: List<DownloadPageEntity>,
)

/**
 * Turns "this chapter has these pages" into rows.
 *
 * Planning is separate from writing because what to write is a pure question — the same chapter and
 * the same pages always produce the same rows — while whether those rows should replace or join the
 * ones already there is not. Keeping them apart is what makes re-enqueueing a chapter safe.
 */
object DownloadPlanner {

    fun plan(
        chapter: ChapterRef,
        title: String,
        comicTitle: String?,
        pages: List<SourcePage>,
        nowEpochMillis: Long,
    ): DownloadPlan {
        val taskId = chapter.taskId()
        val ordered = pages.sortedBy { it.index }
        return DownloadPlan(
            task = DownloadTaskEntity(
                taskId = taskId,
                refSource = refSourceOf(chapter),
                refComic = refComicOf(chapter),
                refChapter = refChapterOf(chapter),
                title = title,
                comicTitle = comicTitle,
                pageCount = ordered.size,
                completedPages = 0,
                state = DownloadChapterState.Queued.name,
                workerId = null,
                heartbeatAt = 0L,
                createdAt = nowEpochMillis,
                updatedAt = nowEpochMillis,
            ),
            pages = ordered.map { page ->
                DownloadPageEntity(
                    taskId = taskId,
                    pageIndex = page.index,
                    imageRef = page.imageRef,
                    state = DownloadPageState.Queued.name,
                    relativePath = null,
                    bytes = 0L,
                    attempts = 0,
                    lastError = null,
                )
            },
        )
    }
}
