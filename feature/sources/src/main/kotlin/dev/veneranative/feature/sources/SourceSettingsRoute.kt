package dev.veneranative.feature.sources

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.veneranative.core.model.SourceId
import dev.veneranative.data.source.SourceCatalogRepository
import dev.veneranative.data.source.SourceRepository
import dev.veneranative.data.settings.ScreenPreferenceRepository
import dev.veneranative.data.comic.ComicCatalog

/**
 * Owns the second level of source management: parameters and maintenance of one source.
 *
 * The ViewModel is the same scoped instance the source list uses, so the configuration loaded on
 * entry is already there and parameter edits survive a trip back to the list. Leaving the page
 * closes the configuration: a pending debounced write is flushed and nothing stays loaded for a
 * source the user is no longer looking at.
 */
@Composable
fun SourceSettingsRoute(
    sourceId: SourceId?,
    repository: SourceRepository,
    catalogRepository: SourceCatalogRepository,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    screenPreferences: ScreenPreferenceRepository? = null,
    comicCatalog: ComicCatalog? = null,
) {
    val viewModel: SourcesViewModel = viewModel {
        SourcesViewModel(repository, catalogRepository, screenPreferences, comicCatalog)
    }
    LaunchedEffect(screenPreferences) { screenPreferences?.let(viewModel::attachScreenPreferences) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    DisposableEffect(sourceId) {
        if (sourceId != null) viewModel.onAction(SourcesAction.OpenConfiguration(sourceId))
        onDispose { viewModel.onAction(SourcesAction.CloseConfiguration) }
    }

    SourceSettingsScreen(
        sourceId = sourceId,
        state = state,
        onAction = viewModel::onAction,
        onBack = onBack,
        modifier = modifier,
    )
}
