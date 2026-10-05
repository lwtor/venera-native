package dev.veneranative.feature.search

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.compose.collectAsLazyPagingItems
import dev.veneranative.core.model.ComicKey
import dev.veneranative.data.comic.ComicCatalog
import dev.veneranative.data.search.SearchHistoryRepository
import dev.veneranative.data.settings.ScreenPreferenceRepository

/**
 * Entry point of the search screen: owns the ViewModel, collects state and results, forwards actions.
 *
 * Results are collected here rather than inside the ViewModel so the screen receives a
 * `LazyPagingItems` and stays a pure renderer of it.
 */
@Composable
fun SearchRoute(
    catalog: ComicCatalog,
    historyRepository: SearchHistoryRepository? = null,
    onOpenComic: (ComicKey) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    screenPreferences: ScreenPreferenceRepository? = null,
) {
    val viewModel: SearchViewModel = viewModel { SearchViewModel(catalog, initialHistoryRepository = historyRepository, screenPreferences = screenPreferences) }
    LaunchedEffect(historyRepository) { historyRepository?.let(viewModel::attachSearchHistory) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(screenPreferences) {
        screenPreferences?.let(viewModel::attachScreenPreferences)
    }
    val results = viewModel.results.collectAsLazyPagingItems()
    val aggregateResults by viewModel.aggregateResults.collectAsStateWithLifecycle()

    if (state.hasSubmitted) {
        SearchResultScreen(
            state = state,
            results = results,
            aggregateResults = aggregateResults,
            onAction = viewModel::onAction,
            onOpenComic = onOpenComic,
            onBackToSearch = {
                if (state.aggregateSourceResultsId != null) {
                    viewModel.onAction(SearchAction.AggregateSourceResultsBack)
                } else {
                    viewModel.onAction(SearchAction.EditSearch)
                }
            },
            modifier = modifier,
        )
    } else {
        SearchScreen(
            state = state,
            onAction = viewModel::onAction,
            onBack = onBack,
            modifier = modifier,
        )
    }
}
