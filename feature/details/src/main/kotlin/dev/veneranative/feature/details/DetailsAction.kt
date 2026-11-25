package dev.veneranative.feature.details

/** Everything the user can do on the details screen. */
sealed interface DetailsAction {

    /** Load again after a failure. */
    data object Retry : DetailsAction

    /** Ask the source for the details again; the chapter list is part of that answer. */
    data object Refresh : DetailsAction
    data class TagClicked(val namespace: String, val tag: String) : DetailsAction
    data object TagNavigationConsumed : DetailsAction
    data object TagMessageConsumed : DetailsAction

    /** Narrow the list to one group, or to every group when [group] is null. */
    data class GroupSelected(val group: String?) : DetailsAction

    data class OrderSelected(val order: ChapterOrder) : DetailsAction

    data class ChapterQueryChanged(val query: String) : DetailsAction

    data object LocateCurrentChapter : DetailsAction
    data class ChapterLocationHandled(val requestId: Long) : DetailsAction

    data class DescriptionExpanded(val expanded: Boolean) : DetailsAction

    data class ChapterSelectionModeChanged(val enabled: Boolean) : DetailsAction

    data class ChapterSelectionToggled(val chapter: dev.veneranative.core.model.ChapterKey) : DetailsAction

    data class ChapterSelectionRangeChanged(
        val start: dev.veneranative.core.model.ChapterKey,
        val end: dev.veneranative.core.model.ChapterKey,
        val selected: Boolean,
    ) : DetailsAction

    data class VisibleChaptersSelected(val selected: Boolean) : DetailsAction

    data object DownloadSelectedChapters : DetailsAction

    /** Opens a collection picker or an explicit removal confirmation. */
    data object ToggleFavorite : DetailsAction
    data object RemoveFavoriteRequested : DetailsAction
    data class FavoriteFolderToggled(val folderId: String) : DetailsAction
    data object ConfirmFavorite : DetailsAction
    data object DismissFavoriteDialog : DetailsAction
    data object NewFavoriteFolderRequested : DetailsAction
    data class NewFavoriteFolderDraftChanged(val draft: String) : DetailsAction
    data object CreateFavoriteFolder : DetailsAction
    data object DismissNewFavoriteFolder : DetailsAction

    /** Opens the panel that looks for this title on the other installed sources. */
    data object OpenSourceSwitch : DetailsAction

    data object CloseSourceSwitch : DetailsAction

    data class SourceSwitchQueryChanged(val query: String) : DetailsAction

    /** Starts or restarts the per-source search with the current query. */
    data object SearchOtherSources : DetailsAction

    /**
     * Picks one search result: rebinds the shelf entry if there is one, then asks the assembly layer
     * to open that comic. There is no separate confirm step — the panel lists nothing but candidates,
     * and tapping one is the choice.
     */
    data class SourceSwitchResultChosen(val comic: dev.veneranative.core.model.Comic) : DetailsAction

    data object SourceSwitchTargetConsumed : DetailsAction
}
