package dev.veneranative.feature.reader

/** User intents accepted by the reader. */
sealed interface ReaderAction {
    data class RetryPage(val index: Int) : ReaderAction
    data object Retry : ReaderAction
    data class PageShown(val index: Int) : ReaderAction
    data class SeekPage(val chapterPageIndex: Int) : ReaderAction
    data object LoadPreviousChapter : ReaderAction
    data object RetryPreviousChapter : ReaderAction
    data object NavigateNextChapter : ReaderAction
    data class ChangeDirection(val direction: ReadingDirection) : ReaderAction
    data object LoadNextChapter : ReaderAction
    data object RetryNextChapter : ReaderAction
}
