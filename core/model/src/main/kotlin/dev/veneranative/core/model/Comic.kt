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
    /** Detail tag categories in the order and grouping declared by the source. */
    val tagGroups: Map<String, List<String>> = emptyMap(),
    /** User-facing scalar facts such as uploader, rating, and update time. */
    val metadata: Map<String, String> = emptyMap(),
    /** Cover images when the source exposes more than one; empty means "use [Comic.coverUrl]". */
    val thumbnails: List<String> = emptyList(),
    /** Related works returned by the source as part of its detail payload. */
    val recommendations: List<Comic> = emptyList(),
    /** Read-only comments included in the detail payload. Mutating them needs source account APIs. */
    val comments: List<ComicComment> = emptyList(),
    /** Source-provided web page for this comic, when available. */
    val sourceUrl: String? = null,
    /** Opaque source reference used by optional account operations; never display this to users. */
    val sourceSubId: String? = null,
)

/** A source-provided comment preview. Account-bound reply and vote operations are separate APIs. */
data class ComicComment(
    val userName: String,
    val content: String,
    val id: String? = null,
    val avatarUrl: String? = null,
    val time: String? = null,
    val replyCount: Int? = null,
    val score: Double? = null,
    val isLiked: Boolean? = null,
    val voteStatus: Int? = null,
)
