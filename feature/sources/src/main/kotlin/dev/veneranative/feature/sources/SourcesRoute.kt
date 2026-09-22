package dev.veneranative.feature.sources

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.veneranative.data.source.SourceRepository

/**
 * Entry point of the sources screen: owns the ViewModel, collects state, forwards actions.
 *
 * The repository is passed in by the assembly layer so the feature never builds its own storage.
 */
@Composable
fun SourcesRoute(
    repository: SourceRepository,
    onBack: () -> Unit,
    onRequestScript: (((String) -> Unit) -> Unit) = {},
    modifier: Modifier = Modifier,
) {
    val viewModel: SourcesViewModel = viewModel { SourcesViewModel(repository) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    SourcesScreen(
        state = state,
        onAction = viewModel::onAction,
        onBack = onBack,
        onChooseScript = {
            onRequestScript { location ->
                viewModel.onAction(SourcesAction.InstallLocationChanged(location))
                viewModel.onAction(SourcesAction.Install)
            }
        },
        modifier = modifier,
    )
}
