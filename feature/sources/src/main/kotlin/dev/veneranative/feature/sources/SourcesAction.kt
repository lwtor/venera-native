package dev.veneranative.feature.sources

import dev.veneranative.core.model.SourceId

/** Intents of the sources screen. */
sealed interface SourcesAction {
    data class InstallLocationChanged(val value: String) : SourcesAction

    data object Install : SourcesAction

    data class SetEnabled(val sourceId: SourceId, val enabled: Boolean) : SourcesAction

    data class Uninstall(val sourceId: SourceId) : SourcesAction

    data object Retry : SourcesAction

    data object DismissMessage : SourcesAction
}
