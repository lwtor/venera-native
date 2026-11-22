package dev.veneranative.core.model

/** A loaded chapter: its display title plus the ordered page descriptors. */
data class ChapterContent(
    val title: String,
    val pages: List<ComicPage>,
    val comicTitle: String? = null,
    val coverUrl: String? = null,
    /**
     * Next chapter in the source's declared order, when available.
     *
     * Typed as [ChapterRef] rather than [Chapter] so locally imported comics can chain chapters
     * too: a local chapter has no [ChapterKey], and treating the missing link as the end of the
     * series is exactly the bug that made downloaded chapters look finished after one chapter.
     */
    val nextChapter: ChapterRef? = null,
    /** Previous chapter in the source's declared order, when available. */
    val previousChapter: ChapterRef? = null,
    /**
     * Whether the lookup behind [nextChapter] / [previousChapter] actually completed.
     *
     * A null neighbour is ambiguous on its own: it can mean "the series ends here" or "we failed
     * to ask". Some sources answer the page request and the chapter-order request through
     * completely different calls, and the order one is far more fragile — copy_manga rebuilds the
     * whole chapter list by re-fetching the comic plus every chapter group, which is exactly the
     * call its rate limiter rejects while single-chapter reads still succeed. Treating such a
     * failure as the end of the series is how a readable chapter gets reported as "last chapter"
     * (or, when the failure is escalated, as an unreadable chapter).
     */
    val neighborsResolved: Boolean = true,
) {
    init {
        require(title.isNotBlank()) { "title must not be blank" }
    }
}

/**
 * A chapter's neighbours in the source's declared order.
 *
 * Null means "the source did not answer", not "there is no such chapter": a real series end is
 * expressed by a neighbour field that is null while the lookup itself succeeded.
 */
data class ChapterNeighbors(
    val previousChapter: ChapterRef?,
    val nextChapter: ChapterRef?,
)

/**
 * Supplies pages for a chapter.
 *
 * It lives in `:core:model` because a data module implements it: `data -> feature` is forbidden, so
 * a source-backed provider can only exist if the contract it implements is below the data layer.
 *
 * Implementations may read from a source runtime, the network, disk or memory. The reader only
 * depends on this contract so the UI never talks to a concrete backend directly.
 */
interface PageProvider {

    suspend fun loadChapter(chapter: ChapterRef): ChapterContent

    /** Warm metadata and the first page of an upcoming chapter before it is opened. */
    suspend fun prefetchChapter(chapter: ChapterRef) = Unit

    /** Resolve one page on demand without changing its stable position in the chapter. */
    suspend fun resolve(page: ComicPage): ComicPage = page

    /** Warm-up for a page that is about to become visible. Default is a no-op. */
    suspend fun prefetch(page: ComicPage) = Unit
}

/** Pixel size of an image reference, resolved by the image pipeline. */
data class ImageSize(val widthPx: Int, val heightPx: Int) {
    init {
        require(widthPx > 0 && heightPx > 0) { "size must be positive" }
    }
}

/**
 * Resolves the real size of a source image reference.
 *
 * Sources only return URLs (`SourcePage.imageRef`), while `ComicPage` requires a size, so this is
 * the seam a page provider uses. It is declared here and implemented in `:core:image`, so a data
 * module never has to depend on the image implementation.
 */
interface PageImageSizer {

    /** The size, or null when the reference could not be resolved and the caller should skip it. */
    suspend fun sizeOf(imageRef: String, sourceId: SourceId): ImageSize?

    /** Source-declared headers must be used both for sizing and subsequent image decoding. */
    suspend fun sizeOf(imageRef: String, sourceId: SourceId, headers: Map<String, String>): ImageSize? =
        sizeOf(imageRef, sourceId)
}

/** A chapter-bound persistence session. Recording only queues immutable data; it must not block UI. */
interface ReaderProgress {
    suspend fun resumePage(): Int
    fun record(content: ChapterContent, pageIndex: Int)
}
