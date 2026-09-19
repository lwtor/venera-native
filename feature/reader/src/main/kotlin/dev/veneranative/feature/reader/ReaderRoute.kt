package dev.veneranative.feature.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.veneranative.core.model.ChapterKey

/**
 * Entry point of the reader: owns the ViewModel, collects state and connects navigation back.
 *
 * The [provider] is passed in so the feature stays independent of any concrete data source.
 */
@Composable
fun ReaderRoute(
    chapter: ChapterKey,
    provider: PageProvider,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: ReaderViewModel = viewModel(key = chapter.value) {
        ReaderViewModel(chapter = chapter, provider = provider)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    ReaderScreen(
        state = state,
        onAction = viewModel::onAction,
        onBack = onBack,
        modifier = modifier,
    )
}
