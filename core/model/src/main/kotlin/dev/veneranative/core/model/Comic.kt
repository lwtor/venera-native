package dev.veneranative.core.model

/** A comic as it appears in a list. It never carries pixels, only a cover reference. */
data class Comic(
    val key: ComicKey,
    val title: String,
    val subtitle: String? = null,
    val coverUrl: String? = null,
    val tags: List<String> = emptyList(),
) {
    init {
        require(title.isNotBlank()) { "title must not be blank" }
    }
}

/**
 * A comic with detail-level metadata.
 *
 * Chapters are not embedded here: they load through the Chapters capability, so opening a detail
 * page does not force every chapter list to be fetched.
 */
data class ComicDetail(
    val comic: Comic,
    val description: String? = null,
    val metadata: Map<String, String> = emptyMap(),
)
