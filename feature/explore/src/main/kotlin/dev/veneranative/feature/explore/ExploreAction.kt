package dev.veneranative.feature.explore

import dev.veneranative.core.model.SourceId

/** Everything the user can do on the explore screen. */
sealed interface ExploreAction {
    data class SourceSelected(val sourceId: SourceId) : ExploreAction

    data class PageSelected(val pageKey: String) : ExploreAction

    data object Retry : ExploreAction
}
