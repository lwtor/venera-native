package dev.veneranative.feature.details

import dev.veneranative.core.model.TagNavigation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.veneranative.core.model.Chapter
import dev.veneranative.core.model.ComicKey
import dev.veneranative.data.collection.CollectionRepository
import dev.veneranative.data.comic.ComicCatalog
import dev.veneranative.data.download.DownloadRepository
import dev.veneranative.data.history.HistoryRepository
import dev.veneranative.data.settings.ScreenPreferenceRepository

/**
 * Entry point of the details screen: owns the ViewModel, collects state, forwards actions.
 *
 * Picking a chapter leaves through [onOpenChapter] as a typed [Chapter]: the screen knows which
 * chapter the user chose, not which screen shows it.
 *
 * [collection] is nullable because the assembly layer builds it from the database, which does not
 * exist for the first moments of the process; until it arrives the comic is readable but cannot be
 * kept, which is better than a screen that waits.
 *
 * [onSwitchSource] leaves through the title the page ended up showing, which is what a search needs
 * when the route's own hint is missing or the source named the work differently.
 *
 * [switchOrigin] is the source a switch started from, and it is what makes this page a candidate:
 * the page offers the commit that settles the switch and nothing else, so the shelf action and the
 * way back into a switch are not beside it. Confirming hands the settled page to
 * [onConfirmSourceSwitch], which drops the switch chain so the page stops being a stop on the way to
 * somewhere else.
 */
@Composable
fun DetailsRoute(
    catalog: ComicCatalog,
    comicKey: ComicKey,
    collection: CollectionRepository?,
    onOpenChapter: (Chapter) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    downloads: DownloadRepository? = null,
    onScheduleDownloads: () -> Unit = {},
    history: HistoryRepository? = null,
    screenPreferences: ScreenPreferenceRepository? = null,
    onOpenDownloads: () -> Unit = {},
    onOpenRelatedWorks: (String, TagNavigation) -> Unit = { _, _ -> },
    onSwitchSource: (String?) -> Unit = {},
    switchOrigin: ComicKey? = null,
    onConfirmSourceSwitch: () -> Unit = {},
) {
    val viewModel: DetailsViewModel = viewModel(key = comicKey.storeKey()) {
        DetailsViewModel(catalog, comicKey, collection, downloads, history, screenPreferences, title)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(state.tagNavigation) {
        state.tagNavigation?.let { navigation ->
            viewModel.onAction(DetailsAction.TagNavigationConsumed)
            onOpenRelatedWorks(navigation.tag, navigation.target)
        }
    }
    androidx.compose.runtime.LaunchedEffect(state.switchConfirmed) {
        if (!state.switchConfirmed) return@LaunchedEffect
        viewModel.onAction(DetailsAction.SourceSwitchConfirmed)
        onConfirmSourceSwitch()
    }
    androidx.compose.runtime.LaunchedEffect(comicKey) {
        viewModel.onScreenEntered()
    }
    androidx.compose.runtime.LaunchedEffect(screenPreferences) {
        screenPreferences?.let(viewModel::attachScreenPreferences)
    }
    androidx.compose.runtime.LaunchedEffect(comicKey, history) {
        viewModel.refreshReadingProgress(history)
    }
    androidx.compose.runtime.LaunchedEffect(state.downloadQueueVersion) {
        if (state.downloadQueueVersion > 0) onScheduleDownloads()
    }

    // The commit's presence is what makes this a candidate page: there is no second flag that could
    // disagree with it.
    val origin = switchOrigin
    DetailsScreen(
        state = state,
        onAction = viewModel::onAction,
        onOpenChapter = onOpenChapter,
        onBack = onBack,
        onOpenDownloads = onOpenDownloads,
        modifier = modifier,
        onSwitchSource = { onSwitchSource(state.title.takeIf(String::isNotBlank)) },
        onConfirmSourceSwitch = if (origin == null) {
            null
        } else {
            { viewModel.onAction(DetailsAction.ConfirmSourceSwitch(origin)) }
        },
    )
}

/**
 * One ViewModel per comic.
 *
 * Ids come from source scripts and may contain any character, so the source id is length-prefixed
 * instead of joined with a separator two different comics could both produce.
 */
private fun ComicKey.storeKey(): String =
    "${sourceId.value.length}:${sourceId.value}${remoteId.value}"
