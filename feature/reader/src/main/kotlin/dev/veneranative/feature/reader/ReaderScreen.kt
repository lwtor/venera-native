package dev.veneranative.feature.reader

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.veneranative.core.designsystem.VeneraNativeTheme
import dev.veneranative.core.model.ComicPage
import dev.veneranative.feature.reader.image.DecodeStrategy
import dev.veneranative.feature.reader.image.DecodedPageImage
import dev.veneranative.feature.reader.image.PageDecodeRequest
import dev.veneranative.feature.reader.image.PageImageDecoder
import dev.veneranative.feature.reader.image.PageRegion
import dev.veneranative.feature.reader.image.PageTile
import dev.veneranative.feature.reader.image.PageTiling
import dev.veneranative.feature.reader.image.PageViewport
import kotlin.math.roundToInt

/** How far panning may travel in continuous mode, expressed in screens of content. */
private const val PAN_HEIGHT_SCREENS = 4f

/**
 * Stateless reader rendering. Every input comes from [state]; every intent leaves as [onAction].
 *
 * [decoderFactory] is the S0-06 validation seam: without it the reader renders page placeholders,
 * with it the reader decodes real pixels. The selected strategy is viewer state, not reader state,
 * and only the enum is saved across recreation — never any pixel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    state: ReaderUiState,
    onAction: (ReaderAction) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    decoderFactory: ((DecodeStrategy) -> PageImageDecoder)? = null,
) {
    var strategy by rememberSaveable { mutableStateOf(DecodeStrategy.Region) }
    val decoder: PageImageDecoder? = decoderFactory?.let { factory ->
        remember(factory, strategy) { factory(strategy) }
    }
    DisposableEffect(decoder) {
        onDispose { decoder?.close() }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = state.chapterTitle.ifEmpty { "Reader" },
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${state.currentPageNumber} / ${state.pageCount}",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Back") }
                },
            )
        },
        bottomBar = {
            if (state.status == ReaderStatus.Ready) {
                ReaderControls(
                    direction = state.direction,
                    strategy = if (decoderFactory == null) null else strategy,
                    onDirectionChange = { onAction(ReaderAction.ChangeDirection(it)) },
                    onStrategyChange = { strategy = it },
                )
            }
        },
    ) { contentPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
            contentAlignment = Alignment.Center,
        ) {
            when (state.status) {
                ReaderStatus.Loading -> CircularProgressIndicator()

                ReaderStatus.Failed -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "This chapter could not be loaded.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Button(onClick = { onAction(ReaderAction.Retry) }) { Text("Retry") }
                }

                ReaderStatus.Ready -> PageContent(
                    state = state,
                    onAction = onAction,
                    decoder = decoder,
                )
            }
        }
    }
}

@Composable
private fun PageContent(
    state: ReaderUiState,
    onAction: (ReaderAction) -> Unit,
    decoder: PageImageDecoder?,
) {
    if (state.pageCount == 0) {
        Text(
            text = "This chapter has no pages.",
            style = MaterialTheme.typography.bodyMedium,
        )
        return
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val viewport = remember(density, maxWidth, maxHeight) {
            with(density) {
                PageViewport(widthPx = maxWidth.roundToPx(), heightPx = maxHeight.roundToPx())
            }
        }
        when (state.direction) {
            ReadingDirection.Vertical -> ContinuousPages(
                state = state,
                viewport = viewport,
                decoder = decoder,
                onAction = onAction,
            )

            ReadingDirection.LeftToRight,
            ReadingDirection.RightToLeft,
            -> SinglePagePager(
                state = state,
                viewport = viewport,
                decoder = decoder,
                onAction = onAction,
            )
        }
    }
}

/** Continuous vertical scrolling, the default reading mode for long strips. */
@Composable
private fun ContinuousPages(
    state: ReaderUiState,
    viewport: PageViewport,
    decoder: PageImageDecoder?,
    onAction: (ReaderAction) -> Unit,
) {
    val zoomState = rememberReaderZoomState()
    val items = remember(state.pages, viewport, zoomState.scale, decoder) {
        state.pages.flatMap { page ->
            val tiles = decoder?.plan(page, viewport, zoomState.scale, continuous = true)
                ?: listOf(placeholderTile(page, viewport, continuous = true))
            tiles.mapIndexed { index, tile -> TileItem(page, index, tile) }
        }
    }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = items
            .indexOfFirst { it.page.index == state.currentPageIndex }
            .coerceAtLeast(0),
    )
    LaunchedEffect(listState, items) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .collect { index -> items.getOrNull(index)?.let { onAction(ReaderAction.PageShown(it.page.index)) } }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(viewport) {
                // panZoomLock keeps single-finger scrolling with the list: a drag is only treated
                // as a pan once a second pointer or a zoom is detected.
                detectTransformGestures(panZoomLock = true) { _, pan, zoomFactor, _ ->
                    zoomState.applyGesture(
                        pan = pan,
                        zoomFactor = zoomFactor,
                        viewport = viewport,
                        contentWidthPx = viewport.widthPx * zoomState.scale,
                        contentHeightPx = viewport.heightPx * zoomState.scale * PAN_HEIGHT_SCREENS,
                    )
                }
            },
        userScrollEnabled = !zoomState.isZoomed,
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        items(items, key = { "${it.page.index}:${it.tileIndex}" }) { item ->
            PageTile(
                item = item,
                decoder = decoder,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
    }
}

/** One page per screen horizontally; RTL is expressed by reversing the layout direction. */
@Composable
private fun SinglePagePager(
    state: ReaderUiState,
    viewport: PageViewport,
    decoder: PageImageDecoder?,
    onAction: (ReaderAction) -> Unit,
) {
    val pagerState = rememberPagerState(
        initialPage = state.currentPageIndex,
        pageCount = { state.pageCount },
    )
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }
            .collect { onAction(ReaderAction.PageShown(it)) }
    }
    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
        reverseLayout = state.direction == ReadingDirection.RightToLeft,
    ) { index ->
        if (decoder == null) {
            PageTile(
                item = TileItem(state.pages[index], 0, placeholderTile(state.pages[index], viewport, false)),
                decoder = null,
            )
        } else {
            ZoomablePage(page = state.pages[index], viewport = viewport, decoder = decoder)
        }
    }
}

/**
 * Paged page with pan and zoom: the decoder is asked for the window around the current pan offset,
 * so zooming in decodes fewer source pixels at a higher scale instead of up-scaling one bitmap.
 */
@Composable
private fun ZoomablePage(
    page: ComicPage,
    viewport: PageViewport,
    decoder: PageImageDecoder,
) {
    val zoomState = rememberReaderZoomState()
    val scale = PageTiling.containScale(page.widthPx, page.heightPx, viewport) * zoomState.scale
    val contentWidthPx = page.widthPx * scale
    val contentHeightPx = page.heightPx * scale
    val tile = remember(page, viewport, zoomState.scale, zoomState.offsetX, zoomState.offsetY, decoder) {
        decoder.planWindow(page, viewport, zoomState.scale, zoomState.offsetX, zoomState.offsetY)
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(viewport) {
                detectTransformGestures(panZoomLock = true) { _, pan, zoomFactor, _ ->
                    zoomState.applyGesture(
                        pan = pan,
                        zoomFactor = zoomFactor,
                        viewport = viewport,
                        contentWidthPx = contentWidthPx,
                        contentHeightPx = contentHeightPx,
                    )
                }
            },
    ) {
        val baseXPx = (viewport.widthPx - contentWidthPx) / 2f + zoomState.offsetX
        val baseYPx = (viewport.heightPx - contentHeightPx) / 2f + zoomState.offsetY
        PageTile(
            item = TileItem(page, 0, tile),
            decoder = decoder,
            modifier = Modifier.offset {
                IntOffset(
                    x = (baseXPx + tile.contentOffsetXPx).roundToInt(),
                    y = (baseYPx + tile.contentOffsetYPx).roundToInt(),
                )
            },
        )
    }
}

/** Result of one tile decode. Kept private so no bitmap reference can leak into reader state. */
private sealed interface PageImageState {
    data class Decoded(val image: DecodedPageImage) : PageImageState
    data object Failed : PageImageState
    data object OutOfMemory : PageImageState
}

@Composable
private fun PageTile(
    item: TileItem,
    decoder: PageImageDecoder?,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val tile = item.tile
    val boxModifier = with(density) {
        modifier.size(width = tile.displayWidthPx.toDp(), height = tile.displayHeightPx.toDp())
    }

    if (decoder == null) {
        Box(
            modifier = boxModifier.background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Page ${item.page.index + 1}",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val imageState by produceState<PageImageState?>(initialValue = null, tile, decoder) {
        value = try {
            val decoded = decoder.decode(
                PageDecodeRequest(
                    path = item.page.imageRef,
                    targetWidthPx = tile.displayWidthPx,
                    targetHeightPx = tile.displayHeightPx,
                    region = tile.region,
                    fitWidthOnly = tile.fitWidthOnly,
                ),
            )
            PageImageState.Decoded(decoded)
        } catch (_: OutOfMemoryError) {
            PageImageState.OutOfMemory
        } catch (_: Exception) {
            PageImageState.Failed
        }
    }

    Box(modifier = boxModifier, contentAlignment = Alignment.Center) {
        when (val current = imageState) {
            null -> CircularProgressIndicator()

            PageImageState.Failed -> Text(
                text = "Page image unavailable",
                style = MaterialTheme.typography.bodyMedium,
            )

            PageImageState.OutOfMemory -> Text(
                text = "Page image is too large to decode",
                style = MaterialTheme.typography.bodyMedium,
            )

            is PageImageState.Decoded -> Image(
                bitmap = current.image.bitmap.asImageBitmap(),
                contentDescription = "Page ${item.page.index + 1}",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds,
            )
        }
    }
}

@Composable
private fun ReaderControls(
    direction: ReadingDirection,
    strategy: DecodeStrategy?,
    onDirectionChange: (ReadingDirection) -> Unit,
    onStrategyChange: (DecodeStrategy) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        ReadingDirection.entries.forEach { candidate ->
            TextButton(onClick = { onDirectionChange(candidate) }) {
                Text(
                    text = candidate.label(),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (candidate == direction) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
        if (strategy != null) {
            DecodeStrategy.entries.forEach { candidate ->
                TextButton(onClick = { onStrategyChange(candidate) }) {
                    Text(
                        text = candidate.label(),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (candidate == strategy) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

private data class TileItem(
    val page: ComicPage,
    val tileIndex: Int,
    val tile: PageTile,
)

private fun placeholderTile(page: ComicPage, viewport: PageViewport, continuous: Boolean): PageTile {
    val scale = if (continuous) {
        PageTiling.fitWidthScale(page.widthPx, viewport.widthPx)
    } else {
        PageTiling.containScale(page.widthPx, page.heightPx, viewport)
    }
    return PageTile(
        region = PageRegion(leftPx = 0, topPx = 0, widthPx = page.widthPx, heightPx = page.heightPx),
        displayWidthPx = (page.widthPx * scale).roundToInt().coerceAtLeast(1),
        displayHeightPx = (page.heightPx * scale).roundToInt().coerceAtLeast(1),
    )
}

private fun ReadingDirection.label(): String = when (this) {
    ReadingDirection.Vertical -> "Vertical"
    ReadingDirection.LeftToRight -> "LTR"
    ReadingDirection.RightToLeft -> "RTL"
}

private fun DecodeStrategy.label(): String = when (this) {
    DecodeStrategy.Sampled -> "Sampled"
    DecodeStrategy.Region -> "Region"
}

@Preview(showBackground = true)
@Composable
private fun ReaderScreenPreview() {
    VeneraNativeTheme {
        ReaderScreen(
            state = ReaderUiState(
                chapterTitle = "Chapter 1",
                pages = List(3) { index ->
                    ComicPage(
                        index = index,
                        imageRef = "preview://$index",
                        widthPx = 1080,
                        heightPx = 1440,
                    )
                },
                status = ReaderStatus.Ready,
            ),
            onAction = {},
            onBack = {},
        )
    }
}
