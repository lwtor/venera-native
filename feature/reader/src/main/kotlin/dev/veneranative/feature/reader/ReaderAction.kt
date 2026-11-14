package dev.veneranative.feature.reader

/** User intents accepted by the reader. */
sealed interface ReaderAction {
    data class RetryPage(val index: Int) : ReaderAction
    data object Retry : ReaderAction
    data class PageShown(val index: Int) : ReaderAction
    data class SeekPage(val chapterPageIndex: Int) : ReaderAction
    data object LoadPreviousChapter : ReaderAction
    data object AutoLoadPreviousChapter : ReaderAction
    data object RetryPreviousChapter : ReaderAction
    data object NavigateNextChapter : ReaderAction
    data class ChangeDirection(val direction: ReadingDirection) : ReaderAction
    data object LoadNextChapter : ReaderAction
    data object RetryNextChapter : ReaderAction
    data object ToggleSplit : ReaderAction
    data class TogglePageSplit(val sourcePageIndex: Int) : ReaderAction
    /** Changes how far each split half reaches past the centre line, as a fraction of the width. */
    data class SetSplitOverlap(val fraction: Float) : ReaderAction
    /** Chooses which half of a split page is shown first for the current comic. */
    data class SetSplitOrder(val rightToLeft: Boolean) : ReaderAction
}
