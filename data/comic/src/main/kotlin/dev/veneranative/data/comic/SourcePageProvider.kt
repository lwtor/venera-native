package dev.veneranative.data.comic

import dev.veneranative.core.model.ChapterContent
import dev.veneranative.core.model.ChapterKey
import dev.veneranative.core.model.ComicPage
import dev.veneranative.core.model.PageImageSizer
import dev.veneranative.core.model.PageProvider
import dev.veneranative.core.model.SourcePage
import dev.veneranative.source.api.SourceOutcome

/**
 * Pages of a chapter as the source describes them.
 *
 * A source answers with image references and nothing else, while `ComicPage` needs a size, so every
 * reference is measured through the [PageImageSizer] before it becomes a descriptor. That seam is
 * also why this provider can live in the data layer: it depends on `:core:model` contracts, not on
 * the image implementation.
 *
 * **A page whose size cannot be resolved is skipped, not failed.** Sources hand out dead URLs, hotlink
 * protected hosts and expired tokens all the time, and one bad page must not make a whole chapter
 * unreadable. When a source itself cannot answer at all, the failure is thrown as
 * [SourceLoadException] so the reader keeps its retry path.
 */
class SourcePageProvider(
    private val catalog: ComicCatalog,
    private val sizer: PageImageSizer,
    /** Display title of a chapter; the remote id is used when the caller has nothing better. */
    private val chapterTitle: suspend (ChapterKey) -> String = { chapter -> chapter.remoteId.value },
) : PageProvider {

    override suspend fun loadChapter(chapter: ChapterKey): ChapterContent {
        val references = when (val outcome = catalog.pages(chapter)) {
            is SourceOutcome.Success -> outcome.value
            is SourceOutcome.Failure -> throw SourceLoadException(outcome.error)
        }
        val pages = references.mapNotNull { reference -> pageOf(reference, chapter) }
        return ChapterContent(title = chapterTitle(chapter), pages = pages)
    }

    private suspend fun pageOf(reference: SourcePage, chapter: ChapterKey): ComicPage? {
        val size = sizer.sizeOf(reference.imageRef, chapter.comicKey.sourceId) ?: return null
        return ComicPage(
            index = reference.index,
            imageRef = reference.imageRef,
            widthPx = size.widthPx,
            heightPx = size.heightPx,
        )
    }
}
