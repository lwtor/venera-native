package dev.veneranative.data.comic

import dev.veneranative.core.model.ChapterContent
import dev.veneranative.core.model.ChapterKey
import dev.veneranative.core.model.ChapterRef
import dev.veneranative.core.model.ComicPage
import dev.veneranative.core.model.PageImageSizer
import dev.veneranative.core.model.PageProvider
import dev.veneranative.core.model.SourcePage
import dev.veneranative.source.api.SourceOutcome

/** Loads chapter references immediately; dimensions and image bytes are resolved per visible page. */
class SourcePageProvider(
    private val catalog: ComicCatalog,
    private val sizer: PageImageSizer,
    /** Display title of a chapter; the remote id is used when the caller has nothing better. */
    private val chapterTitle: suspend (ChapterKey) -> String = { chapter -> chapter.remoteId.value },
) : PageProvider {

    override suspend fun loadChapter(chapter: ChapterRef): ChapterContent {
        val key = (chapter as? ChapterRef.Remote)?.key ?: throw IllegalArgumentException("Source provider only accepts remote chapters")
        val references = when (val outcome = catalog.pages(key)) {
            is SourceOutcome.Success -> outcome.value
            is SourceOutcome.Failure -> throw SourceLoadException(outcome.error)
        }
        val pages = references.mapIndexed { index, reference ->
            ComicPage(index, reference.imageRef, 1080, 1440, key.comicKey.sourceId,
                dev.veneranative.core.model.PageSizeState.Pending)
        }
        val detail = (catalog.detail(key.comicKey) as? SourceOutcome.Success)?.value
        return ChapterContent(
            title = detail?.chapters?.firstOrNull { it.key == key }?.title ?: chapterTitle(key),
            pages = pages, comicTitle = detail?.comic?.title, coverUrl = detail?.comic?.coverUrl,
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
}
