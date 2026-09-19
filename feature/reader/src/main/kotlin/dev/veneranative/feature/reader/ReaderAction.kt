package dev.veneranative.feature.reader

/** User intents accepted by the reader. */
sealed interface ReaderAction {
    data object Retry : ReaderAction
    data class PageShown(val index: Int) : ReaderAction
    data class ChangeDirection(val direction: ReadingDirection) : ReaderAction
}
