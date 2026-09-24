package dev.veneranative.feature.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.veneranative.core.model.ComicKey
import dev.veneranative.data.collection.CollectionRepository
import dev.veneranative.data.local.LocalComicRepository

/**
 * Entry point of the library screen: owns the ViewModel, collects state and forwards navigation.
 *
 * Opening a comic is a callback rather than a route: the assembly layer decides where a comic's
 * details live, so this feature never names another feature.
 */
@Composable
fun LibraryRoute(
    collection: CollectionRepository,
    localRepository: LocalComicRepository? = null,
    onRequestLocalImport: ((String) -> Unit) -> Unit = {},
    onRequestArchiveImport: ((String) -> Unit) -> Unit = {},
    onOpenComic: (ComicKey) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: LibraryViewModel = viewModel { LibraryViewModel(collection, localRepository) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    LibraryScreen(
        state = state,
        onAction = { action ->
            when (action) {
                LibraryAction.RequestLocalImport -> onRequestLocalImport { uri -> viewModel.onAction(LibraryAction.ImportTree(uri)) }
                LibraryAction.RequestArchiveImport -> onRequestArchiveImport { uri -> viewModel.onAction(LibraryAction.ImportArchive(uri)) }
                else -> viewModel.onAction(action)
            }
        },
        onOpenComic = onOpenComic,
        onBack = onBack,
        modifier = modifier,
    )
}
