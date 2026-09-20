package dev.veneranative.core.model

@JvmInline
value class RemoteChapterId(val value: String) {
    init {
        require(value.isNotBlank()) { "RemoteChapterId must not be blank" }
    }
}

/**
 * Identifies a chapter.
 *
 * The comic key is part of the identity because the same remote chapter id can exist under
 * different comics and different sources.
 */
data class ChapterKey(
    val comicKey: ComicKey,
    val remoteId: RemoteChapterId,
)

/** One chapter of a comic, without its pages. */
data class Chapter(
    val key: ChapterKey,
    val title: String,
    val index: Int,
    val group: String? = null,
) {
    init {
        require(index >= 0) { "index must be >= 0" }
        require(title.isNotBlank()) { "title must not be blank" }
    }
}
