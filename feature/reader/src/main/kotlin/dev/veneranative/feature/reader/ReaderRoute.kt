package dev.veneranative.feature.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.veneranative.core.image.decode.PageImageDecoder
import dev.veneranative.core.image.tiling.DecodeStrategy
import dev.veneranative.core.model.ChapterKey
import dev.veneranative.core.model.PageProvider


/**
 * Entry point of the reader: owns the ViewModel, collects state and connects navigation back.
 *
 * The [provider] is passed in so the feature stays independent of any concrete data source.
 * [decoderFactory] is the S0-06 validation seam: it is null while the reader only renders page
 * descriptors, and non-null when real decoding should run.
 */
@Composable
fun ReaderRoute(
    chapter: ChapterKey,
    provider: PageProvider,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    decoderFactory: ((DecodeStrategy) -> PageImageDecoder)? = null,
    /** Where to open a resumed chapter; ignored when it falls outside the loaded pages. */
    startPageIndex: Int = 0,
    /** Throttled persistence seam; null keeps the reader read-only, which is what previews want. */
    progress: dev.veneranative.core.model.ReaderProgress? = null,
    onExit: () -> Unit = {},
) {
    val owner = androidx.compose.runtime.remember(chapter) {
        object : androidx.lifecycle.ViewModelStoreOwner {
            override val viewModelStore = androidx.lifecycle.ViewModelStore()
        }
    }
    val exit by androidx.compose.runtime.rememberUpdatedState(onExit)
    androidx.compose.runtime.DisposableEffect(owner) {
        onDispose { owner.viewModelStore.clear(); exit() }
    }
    val viewModel: ReaderViewModel = viewModel(viewModelStoreOwner = owner) {
        ReaderViewModel(chapter, provider, startPageIndex = startPageIndex, progress = progress)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    ReaderScreen(
        state = state,
        onAction = viewModel::onAction,
        onBack = onBack,
        modifier = modifier,
        decoderFactory = decoderFactory,
    )
}
