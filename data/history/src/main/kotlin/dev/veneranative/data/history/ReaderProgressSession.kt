package dev.veneranative.data.history

import dev.veneranative.core.model.ChapterContent
import dev.veneranative.core.model.ChapterKey
import dev.veneranative.core.model.ChapterRef
import dev.veneranative.core.model.ComicKey
import dev.veneranative.core.model.LOCAL_REF_NAMESPACE
import dev.veneranative.core.model.RemoteChapterId
import dev.veneranative.core.model.RemoteComicId
import dev.veneranative.core.model.SourceId
import dev.veneranative.core.model.ReaderProgress

class ReaderProgressSession(
    private val chapter: ChapterRef,
    private val repository: HistoryRepository,
    private val tracker: ReadingProgressTracker,
    private val clock: () -> Long,
) : ReaderProgress {
    private val comicKey: ComicKey = when (chapter) {
        is ChapterRef.Remote -> chapter.key.comicKey
        is ChapterRef.Local -> ComicKey(SourceId(LOCAL_REF_NAMESPACE), RemoteComicId(chapter.comicId.value))
    }
    private val chapterId: RemoteChapterId = when (chapter) {
        is ChapterRef.Remote -> chapter.key.remoteId
        is ChapterRef.Local -> RemoteChapterId(chapter.chapterId.value)
    }

    constructor(chapter: ChapterKey, repository: HistoryRepository, tracker: ReadingProgressTracker, clock: () -> Long) :
        this(ChapterRef.Remote(chapter), repository, tracker, clock)

    override suspend fun resumePage(): Int = repository.progress(comicKey)
        ?.takeIf { it.chapterId == chapterId }?.pageIndex ?: 0

    override fun record(content: ChapterContent, pageIndex: Int) {
        if (content.pages.isEmpty()) return
        tracker.onPageChanged(ReadingHistoryEntry(
            comicKey = comicKey,
            comicTitle = content.comicTitle ?: comicKey.remoteId.value,
            chapterId = chapterId,
            chapterTitle = content.title,
            coverUrl = content.coverUrl,
            pageIndex = pageIndex,
            pageCount = content.pages.size,
            updatedAtEpochMillis = clock(),
        ))
    }
}
