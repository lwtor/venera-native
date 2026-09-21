package dev.veneranative.core.model

/** A loaded chapter: its display title plus the ordered page descriptors. */
data class ChapterContent(
    val title: String,
    val pages: List<ComicPage>,
) {
    init {
        require(title.isNotBlank()) { "title must not be blank" }
    }
}

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

    suspend fun loadChapter(chapter: ChapterKey): ChapterContent

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
}
