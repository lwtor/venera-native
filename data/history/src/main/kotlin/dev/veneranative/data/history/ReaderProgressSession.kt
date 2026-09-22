package dev.veneranative.data.history

import dev.veneranative.core.model.ChapterContent
import dev.veneranative.core.model.ChapterKey
import dev.veneranative.core.model.ReaderProgress

class ReaderProgressSession(
    private val chapter: ChapterKey,
    private val repository: HistoryRepository,
    private val tracker: ReadingProgressTracker,
    private val clock: () -> Long,
) : ReaderProgress {
    override suspend fun resumePage(): Int = repository.progress(chapter.comicKey)
        ?.takeIf { it.chapterId == chapter.remoteId }?.pageIndex ?: 0

    override fun record(content: ChapterContent, pageIndex: Int) {
        if (content.pages.isEmpty()) return
        tracker.onPageChanged(ReadingHistoryEntry(
            comicKey = chapter.comicKey,
            comicTitle = content.comicTitle ?: chapter.comicKey.remoteId.value,
            chapterId = chapter.remoteId,
            chapterTitle = content.title,
            coverUrl = content.coverUrl,
            pageIndex = pageIndex,
            pageCount = content.pages.size,
            updatedAtEpochMillis = clock(),
        ))
    }
}
