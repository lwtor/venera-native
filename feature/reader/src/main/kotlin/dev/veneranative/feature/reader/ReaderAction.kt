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
    /**
     * Explicitly splits or un-splits one source page; the caller decides, so a stale flip cannot invert it.
     *
     * [split] is the desired end state, not a delta: the dialog already knows what the page should
     * become, and a toggle would re-apply the wrong state if the two exclusion sets ever diverge.
     */
    data class SetPageSplit(val sourcePageIndex: Int, val split: Boolean) : ReaderAction
    /** Changes how far each split half reaches past the centre line, as a fraction of the width. */
    data class SetSplitOverlap(val fraction: Float) : ReaderAction
    /** Chooses which half of a split page is shown first for the current comic. */
    data class SetSplitOrder(val rightToLeft: Boolean) : ReaderAction
}
