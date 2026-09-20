package dev.veneranative.feature.search

import dev.veneranative.core.model.FilterSelection
import dev.veneranative.core.model.InstalledSource
import dev.veneranative.core.model.SourceFilter
import dev.veneranative.core.model.SourceId

/**
 * Everything the search screen needs besides the paged results.
 *
 * The results themselves are not part of this state: they are a Paging 3 stream, and its loading,
 * empty and error states belong to the list rather than to the screen around it.
 */
data class SearchUiState(
    val status: SearchStatus = SearchStatus.Loading,
    /** Installed sources that declare search. Empty in [SearchStatus.Ready] means none do. */
    val sources: List<InstalledSource> = emptyList(),
    val selectedSourceId: SourceId? = null,
    val keyword: String = "",
    /** Filters the selected source declares, in declaration order. */
    val filters: List<SourceFilter> = emptyList(),
    val filterSelection: FilterSelection = FilterSelection.Empty,
    /** Product copy for the last failure; never a lower layer's wording. */
    val message: String? = null,
) {
    val hasSources: Boolean get() = sources.isNotEmpty()

    val canSubmit: Boolean get() = selectedSourceId != null && keyword.isNotBlank()

    val selectedSource: InstalledSource? get() = sources.firstOrNull { it.sourceId == selectedSourceId }
}

enum class SearchStatus {
    Loading,
    Ready,

    /** The source list itself could not be read; retrying is meaningful. */
    Failed,
}
