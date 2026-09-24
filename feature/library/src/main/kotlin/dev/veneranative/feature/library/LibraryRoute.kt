package dev.veneranative.feature.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.veneranative.core.model.ComicKey
import dev.veneranative.data.collection.CollectionRepository
import dev.veneranative.data.local.LocalComicRepository
import dev.veneranative.data.download.DownloadRepository

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
    localRepository: LocalComicRepository? = null,
    downloads: DownloadRepository? = null,
    onRequestLocalImport: ((String) -> Unit) -> Unit = {},
    onRequestArchiveImport: ((String) -> Unit) -> Unit = {},
    onOpenLocalChapter: (dev.veneranative.core.model.LocalComicId, dev.veneranative.core.model.LocalChapterId) -> Unit = { _, _ -> },
    onScheduleDownloads: () -> Unit = {},
) {
    val viewModel: LibraryViewModel = viewModel { LibraryViewModel(collection, localRepository, downloads) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    LibraryScreen(
        state = state,
        onAction = { action ->
            when (action) {
                LibraryAction.RequestLocalImport -> onRequestLocalImport { uri -> viewModel.onAction(LibraryAction.ImportTree(uri)) }
                LibraryAction.RequestArchiveImport -> onRequestArchiveImport { uri -> viewModel.onAction(LibraryAction.ImportArchive(uri)) }
                else -> {
                    viewModel.onAction(action)
                    if (action is LibraryAction.ResumeDownload || action is LibraryAction.RetryDownload) onScheduleDownloads()
                }
            }
        },
        onOpenComic = onOpenComic,
        onOpenLocalChapter = onOpenLocalChapter,
        onBack = onBack,
        modifier = modifier,
    )
}
