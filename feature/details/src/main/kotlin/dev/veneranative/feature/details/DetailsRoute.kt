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
 * [enteredBySourceSwitch] is true when this page sits on top of another source's page for the same
 * comic. Such a page exists to compare sources, so it shows the switch action in place of the shelf
 * action instead of offering both.
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
    onOpenOtherSource: (ComicKey, String?) -> Unit = { _, _ -> },
    enteredBySourceSwitch: Boolean = false,
    showSourceSwitch: Boolean = false,
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
    // Switching sources stacks the comic's page for the new source on top of this one, so back
    // returns here rather than to the list this page came from.
    androidx.compose.runtime.LaunchedEffect(state.sourceSwitchTarget) {
        val target = state.sourceSwitchTarget ?: return@LaunchedEffect
        val hint = state.title.takeIf { it.isNotBlank() }
        viewModel.onAction(DetailsAction.SourceSwitchTargetConsumed)
        onOpenOtherSource(target, hint)
    }
    androidx.compose.runtime.LaunchedEffect(comicKey) {
        viewModel.onScreenEntered()
    }
    // Coming back after switching source has to land on the results that switch started from, not on
    // the comic page they cover. The route asks for it because this screen is rebuilt on the way back.
    androidx.compose.runtime.LaunchedEffect(comicKey, showSourceSwitch) {
        if (showSourceSwitch) viewModel.onAction(DetailsAction.RestoreSourceSwitch)
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

    DetailsScreen(
        state = state,
        onAction = viewModel::onAction,
        onOpenChapter = onOpenChapter,
        onBack = onBack,
        onOpenDownloads = onOpenDownloads,
        modifier = modifier,
        enteredBySourceSwitch = enteredBySourceSwitch,
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
