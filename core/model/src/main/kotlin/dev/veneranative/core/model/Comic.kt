package dev.veneranative.core.model

/** A comic as it appears in a list. It never carries pixels, only a cover reference. */
data class Comic(
    val key: ComicKey,
    val title: String,
    val subtitle: String? = null,
    val coverUrl: String? = null,
    val tags: List<String> = emptyList(),
    /** Page count of the last opened chapter, when the source reports one. */
    val maxPage: Int? = null,
    val language: String? = null,
) {
    init {
        require(title.isNotBlank()) { "title must not be blank" }
    }
}

/**
 * A comic with detail-level metadata.
 *
 * Chapters are embedded because upstream returns them in the same `loadInfo` response
 * (`ComicDetails.chapters` is a map of chapter id to title); the separate Chapters capability exists
 * so callers can refresh the list, and an implementation may answer it from a cached detail.
 */
data class ComicDetail(
    val comic: Comic,
    val description: String? = null,
    val chapters: List<Chapter> = emptyList(),
    val metadata: Map<String, String> = emptyMap(),
    /** Cover images when the source exposes more than one; empty means "use [Comic.coverUrl]". */
    val thumbnails: List<String> = emptyList(),
)
