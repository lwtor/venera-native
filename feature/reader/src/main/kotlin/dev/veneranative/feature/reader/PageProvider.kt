package dev.veneranative.feature.reader

import dev.veneranative.core.model.ChapterKey
import dev.veneranative.core.model.ComicPage

/** A loaded chapter: its display title plus the ordered page descriptors. */
data class ChapterContent(
    val title: String,
    val pages: List<ComicPage>,
)

/**
 * Supplies pages for a chapter.
 *
 * Implementations may read from a source runtime, the network, disk or memory. The reader only
 * depends on this contract so the UI never talks to a concrete backend directly.
 */
interface PageProvider {

    suspend fun loadChapter(chapter: ChapterKey): ChapterContent

    /** Warm-up for a page that is about to become visible. Default is a no-op. */
    suspend fun prefetch(page: ComicPage) = Unit
}
