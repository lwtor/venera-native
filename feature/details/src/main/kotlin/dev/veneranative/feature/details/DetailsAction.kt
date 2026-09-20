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
}
