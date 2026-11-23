package dev.veneranative.feature.sources

import dev.veneranative.core.model.InstalledSource
import dev.veneranative.core.model.SourceId
import dev.veneranative.core.model.InstalledSourceConfiguration
import dev.veneranative.data.source.SourceCatalogEntry
import dev.veneranative.data.source.defaultVeneraSourceCatalogUrl

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
    val configuration: InstalledSourceConfiguration? = null,
    val configurationLoading: Boolean = false,
    val accountActionInProgress: Boolean = false,
    val settingsSaving: Boolean = false,
) {
    val isEmpty: Boolean get() = status == SourcesStatus.Ready && sources.isEmpty()

    val canInstall: Boolean get() = installLocation.isNotBlank() && !installing

    fun installedSourceFor(entry: SourceCatalogEntry): InstalledSource? = sources.firstOrNull { source ->
        entry.key?.let { it == source.sourceId.value } == true || entry.scriptUrl == source.origin
    }

    fun catalogUpdateFor(source: InstalledSource): SourceCatalogEntry? {
        if (catalogStatus != CatalogStatus.Ready) return null
        return catalogEntries.firstOrNull { entry ->
            (entry.key == source.sourceId.value || entry.scriptUrl == source.origin) &&
                entry.version != null && entry.version != source.version
        }
    }

    val catalogUpdateCount: Int get() = sources.count { catalogUpdateFor(it) != null }
}

enum class CatalogStatus { Idle, Loading, Ready, Failed }

enum class SourcesStatus {
    Loading,
    Ready,
    Failed,
}
