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

/**
 * Entry point of the source switcher: owns the ViewModel, collects its rows and results.
 *
 * The pick leaves through [onOpenComic] as a key and title: rebinding the shelf entry has already
 * happened here, and which screen shows a comic is not this feature's business.
 *
 * One ViewModel per comic being left, so the results are still there when the user walks back out of
 * a candidate they rejected.
 */
@Composable
fun SourceSwitchRoute(
    catalog: ComicCatalog,
    comicKey: ComicKey,
    title: String?,
    onMoveShelfEntry: suspend (from: ComicKey, to: ComicKey) -> Unit,
    onOpenComic: (ComicKey, String?) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: SourceSwitchViewModel = viewModel(key = "source-switch:${comicKey.storeKey()}") {
        SourceSwitchViewModel(catalog, comicKey, title, onMoveShelfEntry)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    val results = viewModel.openResults.collectAsLazyPagingItems()
    LaunchedEffect(state.chosen) {
        val choice = state.chosen ?: return@LaunchedEffect
        viewModel.choiceConsumed()
        onOpenComic(choice.comicKey, choice.title)
    }
    SourceSwitchScreen(
        state = state,
        rows = rows,
        results = results,
        onKeywordChanged = viewModel::onKeywordChanged,
        onSearch = viewModel::search,
        onOpenSource = viewModel::openSource,
        onCloseSource = viewModel::closeSource,
        onChooseComic = viewModel::choose,
        onBack = onBack,
        modifier = modifier,
    )
}

/** One ViewModel per comic being left, so candidates for different comics never share results. */
private fun ComicKey.storeKey(): String = "${sourceId.value.length}:${sourceId.value}${remoteId.value}"
