package dev.veneranative.feature.details

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.veneranative.core.model.ChapterKey
import dev.veneranative.core.model.ComicKey
import dev.veneranative.data.collection.CollectionRepository
import dev.veneranative.data.comic.ComicCatalog

/**
 * Entry point of the details screen: owns the ViewModel, collects state, forwards actions.
 *
 * Picking a chapter leaves through [onOpenChapter] as a typed [ChapterKey]: the screen knows which
 * chapter the user chose, not which screen shows it.
 *
 * [collection] is nullable because the assembly layer builds it from the database, which does not
 * exist for the first moments of the process; until it arrives the comic is readable but cannot be
 * kept, which is better than a screen that waits.
 */
@Composable
fun DetailsRoute(
    catalog: ComicCatalog,
    comicKey: ComicKey,
    collection: CollectionRepository?,
    onOpenChapter: (ChapterKey) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: DetailsViewModel = viewModel(key = comicKey.storeKey()) {
        DetailsViewModel(catalog, comicKey, collection)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()

    DetailsScreen(
        state = state,
        onAction = viewModel::onAction,
        onOpenChapter = onOpenChapter,
        onBack = onBack,
        modifier = modifier,
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
