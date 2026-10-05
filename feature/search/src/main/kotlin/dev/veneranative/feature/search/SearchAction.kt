package dev.veneranative.feature.search

import dev.veneranative.core.model.SourceId

/** Everything the user can do on the search screen. */
sealed interface SearchAction {
    data class SourceSelected(val sourceId: SourceId) : SearchAction

    data object AggregateToggled : SearchAction

    data object EditSearch : SearchAction

    data class KeywordChanged(val value: String) : SearchAction

    data class FilterSelected(val key: String, val values: List<String>) : SearchAction

    data class HistorySelected(val keyword: String) : SearchAction

    data class HistoryRemoved(val keyword: String) : SearchAction

    data object HistoryCleared : SearchAction

    data class AggregatedSourceSelected(val sourceId: SourceId) : SearchAction

    /** Returns from a source's full results to the still-open aggregate results. */
    data object AggregateSourceResultsBack : SearchAction

    /** Starts a search with the current source, keyword and filters. */
    data object Submit : SearchAction

    data object Retry : SearchAction
}
