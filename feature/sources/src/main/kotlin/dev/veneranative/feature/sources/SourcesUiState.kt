package dev.veneranative.feature.sources

import dev.veneranative.core.model.InstalledSource
import dev.veneranative.core.model.SourceId

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
) {
    val isEmpty: Boolean get() = status == SourcesStatus.Ready && sources.isEmpty()

    val canInstall: Boolean get() = installLocation.isNotBlank() && !installing
}

enum class SourcesStatus {
    Loading,
    Ready,
    Failed,
}
