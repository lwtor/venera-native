package dev.veneranative.data.collection

import dev.veneranative.core.model.ComicRef

/** What one update check decided about one comic. */
sealed interface UpdateState {

    /** The source could not be asked; whatever is stored stays as it is. */
    data object Unknown : UpdateState

    /**
     * Nothing new. [snapshot] is still worth storing, because a snapshot that goes stale is exactly
     * how a comic gets reported as updated long after the user read it.
     */
    data class Unchanged(val snapshot: ChapterSnapshot) : UpdateState

    /** Chapters appeared since the stored snapshot. */
    data class Updated(val snapshot: ChapterSnapshot) : UpdateState
}

/**
 * Decides whether a comic on the shelf gained chapters.
 *
 * Pure apart from the injected [probe], which is what makes the interesting rules testable on the
 * JVM: a first snapshot is a baseline and never an update, a source that *removes* chapters is not
 * news, and a later id at the same count is (some sources replace a chapter instead of adding one).
 */
class UpdateMarker(
    private val probe: RemoteChapterProbe,
) {

    suspend fun evaluate(stored: FavoriteItem): UpdateState {
        val comicKey = (stored.ref as? ComicRef.Remote)?.key ?: return UpdateState.Unknown
        val remote = probe.chapterSnapshot(comicKey) ?: return UpdateState.Unknown

        val previousCount = stored.chapterCount
        val previousLatest = stored.latestChapterId
        if (previousCount == null || previousLatest == null) return UpdateState.Unchanged(remote)
        if (remote.chapterCount < previousCount) return UpdateState.Unchanged(remote)
        if (remote.chapterCount > previousCount) return UpdateState.Updated(remote)

        val latest = remote.latestChapterId
        val replaced = latest != null && latest != previousLatest
        return if (replaced) UpdateState.Updated(remote) else UpdateState.Unchanged(remote)
    }
}
