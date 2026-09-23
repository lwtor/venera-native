package dev.veneranative.core.model

@JvmInline
value class LocalChapterId(val value: String) {
    init {
        require(value.isNotBlank()) { "LocalChapterId must not be blank" }
    }
}

/**
 * Identifies a chapter the app can open, wherever its pages come from.
 *
 * Sealed for the same reason [ComicRef] is: a caller must not be able to hand a locally imported id
 * to a source call and get a plausible-looking failure, nor hand a remote key to a file-based
 * provider and get a file that does not exist. Each implementation handles the half it owns.
 *
 * Downloads only ever hold [Remote] chapters — they are fetched from a source — but the download
 * tables encode the identity as three string columns, so a local chapter can be recorded later
 * without another migration.
 */
sealed interface ChapterRef {

    data class Remote(val key: ChapterKey) : ChapterRef

    data class Local(val comicId: LocalComicId, val chapterId: LocalChapterId) : ChapterRef

    val comicRef: ComicRef
        get() = when (this) {
            is Remote -> ComicRef.Remote(key.comicKey)
            is Local -> ComicRef.Local(comicId)
        }
}
