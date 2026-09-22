package dev.veneranative.feature.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.veneranative.core.model.ComicKey
import dev.veneranative.data.collection.CollectionRepository

/**
 * Entry point of the library screen: owns the ViewModel, collects state and forwards navigation.
 *
 * Opening a comic is a callback rather than a route: the assembly layer decides where a comic's
 * details live, so this feature never names another feature.
 */
@Composable
fun LibraryRoute(
    collection: CollectionRepository,
    onOpenComic: (ComicKey) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: LibraryViewModel = viewModel { LibraryViewModel(collection) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    LibraryScreen(
        state = state,
        onAction = viewModel::onAction,
        onOpenComic = onOpenComic,
        onBack = onBack,
        modifier = modifier,
    )
}
