package dev.veneranative.core.model

/**
 * Per-chapter persistence seam for the reader's "auto split double pages" feature.
 *
 * The reader depends on this contract (declared in `:core:model`) so it never talks to the data
 * layer directly. A concrete implementation stores preferences keyed by [ChapterRef].
 */
interface SplitPreferenceRepository {

    /** Whether double pages should be split for this chapter. */
    suspend fun isSplitEnabled(chapterRef: ChapterRef): Boolean

    suspend fun setSplitEnabled(chapterRef: ChapterRef, enabled: Boolean)

    /** Whether a specific source page is manually excluded from splitting (or re-included). */
    suspend fun isPageExcluded(chapterRef: ChapterRef, sourcePageIndex: Int): Boolean

    suspend fun setPageExcluded(chapterRef: ChapterRef, sourcePageIndex: Int, excluded: Boolean)
}

/**
 * Stable, collision-free identity for a chapter, used to key split preferences.
 *
 * Remote and local chapters never collide: they are prefixed with `remote:` and `local:`
 * respectively, mirroring the `LOCAL_REF_NAMESPACE` separation used elsewhere.
 */
fun ChapterRef.stableId(): String = when (this) {
    is ChapterRef.Remote -> buildString {
        append("remote:")
        append(key.comicKey.sourceId.value)
        append('/')
        append(key.comicKey.remoteId.value)
        append('/')
        append(key.remoteId.value)
        val group = this@stableId.group
        if (group != null) {
            append('/')
            append(group)
        }
    }
    is ChapterRef.Local -> "local:${comicId.value}/${chapterId.value}"
}
