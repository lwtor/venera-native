package dev.veneranative.feature.search

import dev.veneranative.core.model.SourceId

/** Everything the user can do on the search screen. */
sealed interface SearchAction {
    data class SourceSelected(val sourceId: SourceId) : SearchAction

    data class KeywordChanged(val value: String) : SearchAction

    /** Starts a search with the current source, keyword and filters. */
    data object Submit : SearchAction

    data object Retry : SearchAction
}
