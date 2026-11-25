package dev.veneranative.feature.search

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.compose.collectAsLazyPagingItems
import dev.veneranative.core.model.ComicKey
import dev.veneranative.data.comic.ComicCatalog

/**
 * Entry point of the source switcher: owns the ViewModel, collects its rows and results.
 *
 * The pick leaves through [onOpenComic] as a key and title. Nothing is migrated here: opening a
 * candidate is looking, and the candidate page is where the user settles the switch.
 *
 * One ViewModel per comic being left, so the results are still there — and still where the user left
 * them — when they walk back out of a candidate they rejected.
 */
@Composable
fun SourceSwitchRoute(
    catalog: ComicCatalog,
    comicKey: ComicKey,
    title: String?,
    onOpenComic: (ComicKey, String?) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: SourceSwitchViewModel = viewModel(key = "source-switch:${comicKey.storeKey()}") {
        SourceSwitchViewModel(catalog, comicKey, title)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    val results = viewModel.openResults.collectAsLazyPagingItems()
    SourceSwitchScreen(
        state = state,
        rows = rows,
        results = results,
        overviewState = viewModel.keptScroll(OVERVIEW_SCROLL_SLOT),
        sourceState = { sourceId -> viewModel.keptScroll(SOURCE_SCROLL_SLOT_PREFIX + sourceId.value) },
        rowState = { sourceId -> viewModel.keptScroll(rowScrollSlot(sourceId)) },
        onKeywordChanged = viewModel::onKeywordChanged,
        onSearch = viewModel::search,
        onOpenSource = viewModel::openSource,
        onCloseSource = viewModel::closeSource,
        onChooseComic = { comic -> onOpenComic(comic.key, comic.title) },
        onBack = onBack,
        modifier = modifier,
    )
}

/**
 * A list state that starts where [slot] was left and keeps it up to date.
 *
 * The screen is rebuilt from scratch whenever the user comes back from a candidate, so the position
 * cannot live in the composition; it lives beside the results instead and is read back here.
 */
@Composable
private fun SourceSwitchViewModel.keptScroll(slot: String): LazyListState {
    val start = remember(slot) { scrollSlot(slot) }
    val state = rememberLazyListState(start.index, start.offset)
    LaunchedEffect(state, slot) {
        snapshotFlow {
            Triple(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset, state.layoutInfo.totalItemsCount)
        }
            // A list that has not been given its items yet measures as "at the top"; writing that
            // over the stored position would lose it before there is anything to scroll back to.
            .collect { (index, offset, count) -> if (count > 0) keepScrollSlot(slot, index, offset) }
    }
    return state
}

/** One ViewModel per comic being left, so candidates for different comics never share results. */
private fun ComicKey.storeKey(): String = "${sourceId.value.length}:${sourceId.value}${remoteId.value}"
