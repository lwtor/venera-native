package dev.veneranative.feature.sources

import dev.veneranative.core.model.SourceId
import dev.veneranative.data.source.SourceCatalogEntry
import dev.veneranative.data.comic.CatalogComicMatch

/** Intents of the sources screen. */
sealed interface SourcesAction {
    data class InstallLocationChanged(val value: String) : SourcesAction

    data object Install : SourcesAction

    data class SetEnabled(val sourceId: SourceId, val enabled: Boolean) : SourcesAction

    data class ReorderSources(val sourceIds: List<SourceId>) : SourcesAction

    data class Uninstall(val sourceId: SourceId) : SourcesAction

    data object Retry : SourcesAction

    data object DismissMessage : SourcesAction

    data class CatalogLocationChanged(val value: String) : SourcesAction

    data class SelectCatalogLocation(val value: String) : SourcesAction

    data object RefreshCatalog : SourcesAction

    data class InstallCatalogEntry(val entry: SourceCatalogEntry) : SourcesAction

    data class ComicQueryChanged(val query: String) : SourcesAction
    data object SearchCatalogForComic : SourcesAction
    data object EditCatalogComicSearch : SourcesAction
    data object CancelCatalogComicSearch : SourcesAction
    data class InstallComicMatch(val match: CatalogComicMatch) : SourcesAction

    data class OpenConfiguration(val sourceId: SourceId) : SourcesAction
    data object CloseConfiguration : SourcesAction
    data class SaveSettings(val sourceId: SourceId, val values: Map<String, String>) : SourcesAction
    data class InvokeSettingCallback(val sourceId: SourceId, val key: String) : SourcesAction
    data class Login(val sourceId: SourceId, val account: String, val password: String) : SourcesAction
    data class Relogin(val sourceId: SourceId) : SourcesAction
    data class CheckLogin(val sourceId: SourceId) : SourcesAction
    data class Logout(val sourceId: SourceId) : SourcesAction
}
