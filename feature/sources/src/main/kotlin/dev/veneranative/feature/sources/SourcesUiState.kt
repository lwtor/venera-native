package dev.veneranative.feature.sources

import dev.veneranative.core.model.InstalledSource
import dev.veneranative.core.model.SourceId
import dev.veneranative.core.model.InstalledSourceConfiguration
import dev.veneranative.data.source.SourceCatalogEntry
import dev.veneranative.data.source.defaultVeneraSourceCatalogUrl
import dev.veneranative.data.comic.CatalogComicMatch
import dev.veneranative.data.comic.CatalogProbeFailureStage

/** Everything the sources screen needs to render, including its own empty state. */
data class SourcesUiState(
    val status: SourcesStatus = SourcesStatus.Loading,
    val sources: List<InstalledSource> = emptyList(),
    /** Product copy for the last failure; never a lower layer's wording. */
    val message: String? = null,
    /** Location the user typed, kept in state so a failed install does not clear it. */
    val installLocation: String = "",
    val installing: Boolean = false,
    /** Sources with a pending enable/disable or uninstall. */
    val busySourceIds: Set<SourceId> = emptySet(),
    val catalogEntries: List<SourceCatalogEntry> = emptyList(),
    val catalogStatus: CatalogStatus = CatalogStatus.Idle,
    val catalogLocation: String = defaultVeneraSourceCatalogUrl(),
    val catalogHistory: List<String> = emptyList(),
    val comicQuery: String = "",
    val comicSearchMatches: List<CatalogComicMatch> = emptyList(),
    val comicSearchLoading: Boolean = false,
    val comicSearchPerformed: Boolean = false,
    val comicSearchCompletedSources: Int = 0,
    val comicSearchTotalSources: Int = 0,
    val comicSearchFailedSources: Int = 0,
    val comicSearchFailuresByStage: Map<CatalogProbeFailureStage, Int> = emptyMap(),
    val configuration: InstalledSourceConfiguration? = null,
    val configurationLoading: Boolean = false,
    val accountActionInProgress: Boolean = false,
    val loginCheckOutcome: dev.veneranative.data.source.SourceLoginCheckOutcome? = null,
    val loginCheckOutcomes: Map<SourceId, dev.veneranative.data.source.SourceLoginCheckOutcome> = emptyMap(),
    val loginCheckInProgressSourceIds: Set<SourceId> = emptySet(),
    val manualLoginCheckSourceIds: Set<SourceId> = emptySet(),
    val settingsSaving: Boolean = false,
) {
    val isEmpty: Boolean get() = status == SourcesStatus.Ready && sources.isEmpty()

    val canInstall: Boolean get() = installLocation.isNotBlank() && !installing

    fun installedSourceFor(entry: SourceCatalogEntry): InstalledSource? = sources.firstOrNull { source ->
        if (entry.key != null) entry.key == source.sourceId.value else entry.scriptUrl == source.origin
    }

    fun catalogUpdateFor(source: InstalledSource): SourceCatalogEntry? {
        if (catalogStatus != CatalogStatus.Ready || source.catalogLocation == null) return null
        return catalogEntries.firstOrNull { entry ->
            (if (entry.key != null) entry.key == source.sourceId.value else entry.scriptUrl == source.origin) &&
                entry.catalogLocation == source.catalogLocation &&
                entry.version != null && entry.version != source.version
        }
    }

    fun isFromDifferentCatalog(entry: SourceCatalogEntry): Boolean =
        installedSourceFor(entry)?.catalogLocation?.let { installedCatalog ->
            entry.catalogLocation != null && installedCatalog != entry.catalogLocation
        } ?: false

    val catalogUpdateCount: Int get() = sources.count { catalogUpdateFor(it) != null }

}

enum class CatalogStatus { Idle, Loading, Ready, Failed }

enum class SourcesStatus {
    Loading,
    Ready,
    Failed,
}
