package dev.veneranative.feature.details

/** Everything the user can do on the details screen. */
sealed interface DetailsAction {

    /** Load again after a failure. */
    data object Retry : DetailsAction

    /** Ask the source for the details again; the chapter list is part of that answer. */
    data object Refresh : DetailsAction

    /** Narrow the list to one group, or to every group when [group] is null. */
    data class GroupSelected(val group: String?) : DetailsAction

    data class OrderSelected(val order: ChapterOrder) : DetailsAction

    /**
     * Keep the comic, or stop keeping it.
     *
     * One action rather than a folder picker: which folder a comic lives in is the shelf's job, and a
     * screen that only ever sees one comic has nothing to choose between.
     */
    data object ToggleFavorite : DetailsAction

    data class DownloadChapter(val chapter: dev.veneranative.core.model.ChapterKey) : DetailsAction
}
