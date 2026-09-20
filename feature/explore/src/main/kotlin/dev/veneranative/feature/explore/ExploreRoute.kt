package dev.veneranative.feature.explore

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.compose.collectAsLazyPagingItems
import dev.veneranative.core.model.ComicKey
import dev.veneranative.data.comic.ComicCatalog

/**
 * Entry point of the explore screen: owns the ViewModel, collects state and content, forwards actions.
 */
@Composable
fun ExploreRoute(
    catalog: ComicCatalog,
    onOpenComic: (ComicKey) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: ExploreViewModel = viewModel { ExploreViewModel(catalog) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val results = viewModel.results.collectAsLazyPagingItems()

    ExploreScreen(
        state = state,
        results = results,
        onAction = viewModel::onAction,
        onOpenComic = onOpenComic,
        onBack = onBack,
        modifier = modifier,
    )
}
