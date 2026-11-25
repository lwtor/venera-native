package dev.veneranative.feature.search

import dev.veneranative.core.model.InstalledSource
import dev.veneranative.core.model.SourceId

/**
 * Everything the source switcher renders.
 *
 * [candidates] excludes the source the comic came from, so the page never offers to switch to the
 * source being left. The rows themselves are not here: they arrive from the shared aggregate search,
 * the same one the search screen runs, and are collected beside this state.
 */
data class SourceSwitchUiState(
    val keyword: String = "",
    val candidates: List<InstalledSource> = emptyList(),
    /** True once the installed-source list has been read; until then the page cannot search. */
    val ready: Boolean = false,
    /** Source whose full result list is open, or null for the grouped overview. */
    val openedSourceId: SourceId? = null,
    /** Product copy for the last failure; never a lower layer's wording. */
    val message: String? = null,
) {
    val canSearch: Boolean get() = keyword.isNotBlank() && candidates.isNotEmpty()

    val openedSourceName: String? get() = candidates.firstOrNull { it.sourceId == openedSourceId }?.name
}

/**
 * Where a scrollable area of the switcher was left.
 *
 * The page is rebuilt whenever the user comes back from a candidate they did not take, and a
 * rebuilt list starts at the top — which costs them the result they were about to look at. The
 * position lives on the ViewModel beside the results it belongs to, so the two stay in step:
 * searching again starts at the top because the results are new, coming back does not.
 */
data class ScrollSlot(val index: Int = 0, val offset: Int = 0)
