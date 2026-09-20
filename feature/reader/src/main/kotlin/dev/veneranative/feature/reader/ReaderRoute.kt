package dev.veneranative.feature.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.veneranative.core.model.ChapterKey
import dev.veneranative.feature.reader.image.DecodeStrategy
import dev.veneranative.feature.reader.image.PageImageDecoder

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
) {
    val viewModelKey =
        "${chapter.comicKey.sourceId.value}:${chapter.comicKey.remoteId.value}:${chapter.remoteId.value}"
    val viewModel: ReaderViewModel = viewModel(key = viewModelKey) {
        ReaderViewModel(chapter = chapter, provider = provider)
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
