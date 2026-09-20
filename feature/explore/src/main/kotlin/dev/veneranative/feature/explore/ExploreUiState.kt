package dev.veneranative.feature.explore

import dev.veneranative.core.model.ExplorePage
import dev.veneranative.core.model.InstalledSource
import dev.veneranative.core.model.SourceId

/**
 * Everything the explore screen needs besides the paged content.
 *
 * Explore pages come from the source, not from the app: a source declares its own pages, their order
 * and how each one paginates, so the tabs are [pages] rather than a fixed list.
 */
data class ExploreUiState(
    val status: ExploreStatus = ExploreStatus.Loading,
    /** Installed sources that declare at least one explore page. */
    val sources: List<InstalledSource> = emptyList(),
    val selectedSourceId: SourceId? = null,
    val pages: List<ExplorePage> = emptyList(),
    val selectedPageKey: String? = null,
    /** Product copy for the last failure; never a lower layer's wording. */
    val message: String? = null,
) {
    val hasSources: Boolean get() = sources.isNotEmpty()

    val hasPages: Boolean get() = pages.isNotEmpty()

    val selectedPage: ExplorePage? get() = pages.firstOrNull { it.key == selectedPageKey }
}

enum class ExploreStatus {
    Loading,
    Ready,

    /** The source list itself could not be read; retrying is meaningful. */
    Failed,
}
