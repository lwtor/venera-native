package dev.veneranative.feature.sources

import dev.veneranative.core.model.SourceId
import dev.veneranative.data.source.SourceCatalogEntry

/** Intents of the sources screen. */
sealed interface SourcesAction {
    data class InstallLocationChanged(val value: String) : SourcesAction

    data object Install : SourcesAction

    data class SetEnabled(val sourceId: SourceId, val enabled: Boolean) : SourcesAction

    data class Uninstall(val sourceId: SourceId) : SourcesAction

    data object Retry : SourcesAction

    data object DismissMessage : SourcesAction

    data class CatalogLocationChanged(val value: String) : SourcesAction

    data object RefreshCatalog : SourcesAction

    data class InstallCatalogEntry(val entry: SourceCatalogEntry) : SourcesAction

    data class OpenConfiguration(val sourceId: SourceId) : SourcesAction
    data object CloseConfiguration : SourcesAction
    data class SaveSettings(val sourceId: SourceId, val values: Map<String, String>) : SourcesAction
    data class InvokeSettingCallback(val sourceId: SourceId, val key: String) : SourcesAction
    data class Login(val sourceId: SourceId, val account: String, val password: String) : SourcesAction
    data class Logout(val sourceId: SourceId) : SourcesAction
}
