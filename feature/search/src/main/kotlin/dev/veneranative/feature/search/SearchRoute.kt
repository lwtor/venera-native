package dev.veneranative.feature.search

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.compose.collectAsLazyPagingItems
import dev.veneranative.core.model.ComicKey
import dev.veneranative.data.comic.ComicCatalog

/**
 * Entry point of the search screen: owns the ViewModel, collects state and results, forwards actions.
 *
 * Results are collected here rather than inside the ViewModel so the screen receives a
 * `LazyPagingItems` and stays a pure renderer of it.
 */
@Composable
fun SearchRoute(
    catalog: ComicCatalog,
    onOpenComic: (ComicKey) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: SearchViewModel = viewModel { SearchViewModel(catalog) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val results = viewModel.results.collectAsLazyPagingItems()

    SearchScreen(
        state = state,
        results = results,
        onAction = viewModel::onAction,
        onOpenComic = onOpenComic,
        onBack = onBack,
        modifier = modifier,
    )
}
