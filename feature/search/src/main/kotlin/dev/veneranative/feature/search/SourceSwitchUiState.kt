package dev.veneranative.feature.search

import dev.veneranative.core.model.ComicKey
import dev.veneranative.core.model.InstalledSource
import dev.veneranative.core.model.SourceId

/** The comic the user settled on, handed to the assembly layer as a navigation request. */
data class SourceSwitchChoice(val comicKey: ComicKey, val title: String?)

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
    val choosing: Boolean = false,
    val chosen: SourceSwitchChoice? = null,
) {
    val canSearch: Boolean get() = keyword.isNotBlank() && candidates.isNotEmpty()

    val openedSourceName: String? get() = candidates.firstOrNull { it.sourceId == openedSourceId }?.name
}
