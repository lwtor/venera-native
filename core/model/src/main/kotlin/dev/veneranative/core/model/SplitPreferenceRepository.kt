package dev.veneranative.core.model

/**
 * Persistence seam for the reader's "auto split double pages" feature.
 *
 * Chapter-level settings ([isSplitEnabled], [isPageExcluded]) are keyed by [ChapterRef]; the gutter
 * overlap and half order are shared by the whole comic and keyed by [ComicRef]. The reader depends
 * on this contract (declared in `:core:model`) so it never talks to the data layer directly.
 */
interface SplitPreferenceRepository {

    /** Whether double pages should be split for this chapter. */
    suspend fun isSplitEnabled(chapterRef: ChapterRef): Boolean

    suspend fun setSplitEnabled(chapterRef: ChapterRef, enabled: Boolean)

    /** Whether a specific source page is manually excluded from splitting (or re-included). */
    suspend fun isPageExcluded(chapterRef: ChapterRef, sourcePageIndex: Int): Boolean

    suspend fun setPageExcluded(chapterRef: ChapterRef, sourcePageIndex: Int, excluded: Boolean)

    /**
     * How far each half reaches past the centre line, as a fraction of the page width.
     *
     * Defaults to [PageSplit.DEFAULT_OVERLAP_FRACTION] so a repository that only stores chapter
     * settings keeps working, and so implementations without persistence stay valid fakes.
     */
    suspend fun overlapFraction(comicRef: ComicRef): Float = PageSplit.DEFAULT_OVERLAP_FRACTION

    suspend fun setOverlapFraction(comicRef: ComicRef, fraction: Float) = Unit

    /** Whether the right half is shown before the left half when reading this comic. */
    suspend fun isRightToLeft(comicRef: ComicRef): Boolean = false

    suspend fun setRightToLeft(comicRef: ComicRef, rightToLeft: Boolean) = Unit
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

/**
 * Stable, collision-free identity for a comic, used to key per-comic split preferences.
 *
 * Every chapter of the same comic resolves to the same value, while two comics — local or remote —
 * never share one.
 */
fun ComicRef.stableId(): String = when (this) {
    is ComicRef.Remote -> "remote:${key.sourceId.value}/${key.remoteId.value}"
    is ComicRef.Local -> "local:${id.value}"
}
