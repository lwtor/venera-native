package dev.veneranative.data.comic

import dev.veneranative.core.model.ChapterContent
import dev.veneranative.core.model.ChapterKey
import dev.veneranative.core.model.ChapterRef
import dev.veneranative.core.model.ComicPage
import dev.veneranative.core.model.PageImageSizer
import dev.veneranative.core.model.PageProvider
import dev.veneranative.core.model.SourcePage
import dev.veneranative.source.api.SourceOutcome
import dev.veneranative.source.api.SourceRuntimeError
import kotlinx.coroutines.delay

/** Loads chapter references immediately; dimensions and image bytes are resolved per visible page. */
class SourcePageProvider(
    private val catalog: ComicCatalog,
    private val sizer: PageImageSizer,
    /** Display title of a chapter; the remote id is used when the caller has nothing better. */
    private val chapterTitle: suspend (ChapterKey) -> String = { chapter -> chapter.remoteId.value },
    /** Retriable contention occurs briefly when a previous screen is cancelling a source call. */
    private val waitBeforeBusyRetry: suspend (attempt: Int) -> Unit = { attempt -> delay(attempt * 150L) },
) : PageProvider {

    override suspend fun loadChapter(chapter: ChapterRef): ChapterContent {
        val key = (chapter as? ChapterRef.Remote)?.key ?: throw IllegalArgumentException("Source provider only accepts remote chapters")
        var busyRetries = 0
        var references: List<SourcePage>? = null
        while (references == null) {
            when (val outcome = catalog.pages(key)) {
                is SourceOutcome.Success -> references = outcome.value
                is SourceOutcome.Failure -> {
                    if (outcome.error !is SourceRuntimeError.Busy || busyRetries >= MAX_BUSY_RETRIES) {
                        throw SourceLoadException(outcome.error)
                    }
                    busyRetries++
                    waitBeforeBusyRetry(busyRetries)
                }
            }
        }
        val pages = references.orEmpty().mapIndexed { index, reference ->
            ComicPage(index, reference.imageRef, 1080, 1440, key.comicKey.sourceId,
                dev.veneranative.core.model.PageSizeState.Pending)
        }
        val detail = (catalog.detail(key.comicKey) as? SourceOutcome.Success)?.value
        val chapterIndex = detail?.chapters?.indexOfFirst { it.key == key } ?: -1
        return ChapterContent(
            title = detail?.chapters?.firstOrNull { it.key == key }?.title ?: chapterTitle(key),
            pages = pages, comicTitle = detail?.comic?.title, coverUrl = detail?.comic?.coverUrl,
            nextChapter = detail?.chapters?.getOrNull(chapterIndex + 1).takeIf { chapterIndex >= 0 },
        )
    }

    suspend fun loadChapter(chapter: ChapterKey): ChapterContent = loadChapter(ChapterRef.Remote(chapter))

    override suspend fun resolve(page: ComicPage): ComicPage {
        val sourceId = requireNotNull(page.sourceId)
        val size = sizer.sizeOf(page.imageRef, sourceId)
            ?: throw java.io.IOException("Page image unavailable")
        return page.copy(widthPx = size.widthPx, heightPx = size.heightPx,
            sizeState = dev.veneranative.core.model.PageSizeState.Ready)
    }

    override suspend fun prefetch(page: ComicPage) { resolve(page) }

    private companion object {
        const val MAX_BUSY_RETRIES = 2
    }
}
