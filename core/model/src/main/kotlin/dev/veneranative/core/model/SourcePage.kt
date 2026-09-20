package dev.veneranative.core.model

/**
 * A page exactly as the source describes it: an image reference and its position.
 *
 * Dimensions are deliberately absent. `loadEp` returns URL strings only, so the real size is known
 * only after the image pipeline has resolved it — [ComicPage] is the reader-facing descriptor that
 * carries a size and is produced once that happens.
 */
data class SourcePage(
    val index: Int,
    val imageRef: String,
) {
    init {
        require(index >= 0) { "index must be >= 0" }
        require(imageRef.isNotBlank()) { "imageRef must not be blank" }
    }
}
