package dev.veneranative.core.model

/**
 * Describes a single page without holding decoded pixel data.
 *
 * [imageRef] is an opaque reference resolved by whoever renders the page; the descriptor itself
 * never carries a Bitmap, so it stays safe to keep in Compose state and SavedStateHandle.
 */
data class ComicPage(
    val index: Int,
    val imageRef: String,
    val widthPx: Int,
    val heightPx: Int,
    val sourceId: SourceId? = null,
    val sizeState: PageSizeState = PageSizeState.Ready,
    val imageHeaders: Map<String, String> = emptyMap(),
    val reverseHorizontalBands: Int? = null,
    /** Non-null when this display page is a crop of a larger source image, e.g. one half of a double page. */
    val crop: PageCrop? = null,
    /**
     * Index of this display page in the source chapter. It stays equal to [index] for an un-split
     * page; when a double page is split, both halves share the same source index so progress and
     * per-page exclusions can still address the source page.
     */
    val sourceIndex: Int = index,
) {
    init {
        require(index >= 0) { "index must be >= 0" }
        require(widthPx > 0) { "widthPx must be > 0" }
        require(heightPx > 0) { "heightPx must be > 0" }
        require(sourceIndex >= 0) { "sourceIndex must be >= 0" }
    }

    /** Width divided by height, used to reserve space before the image is decoded. */
    val aspectRatio: Float get() = widthPx.toFloat() / heightPx.toFloat()
}

/** Unknown sizes use a layout estimate until the visible neighbourhood is resolved. */
enum class PageSizeState { Pending, Ready, Failed }
