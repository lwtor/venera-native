package dev.veneranative.feature.reader

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.veneranative.core.designsystem.VeneraNativeTheme
import dev.veneranative.core.image.decode.PageImageDecoder
import dev.veneranative.core.image.tiling.DecodeStrategy
import dev.veneranative.core.image.tiling.DecodedPageImage
import dev.veneranative.core.image.tiling.PageDecodeRequest
import dev.veneranative.core.image.tiling.PageRegion
import dev.veneranative.core.image.tiling.PageTile
import dev.veneranative.core.image.tiling.PageTiling
import dev.veneranative.core.image.tiling.PageViewport
import dev.veneranative.core.model.ComicPage
import dev.veneranative.core.model.PageSizeState
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.roundToInt


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
    var chapterEndReached by remember(state.chapterTitle) { mutableStateOf(false) }
    val decoder: PageImageDecoder? = decoderFactory?.let { factory ->
        remember(factory, strategy) { factory(strategy) }
    }
    DisposableEffect(decoder) {
        onDispose { decoder?.close() }
    }
    LaunchedEffect(chapterEndReached, state.nextChapter) {
        if (chapterEndReached && state.nextChapter != null) {
            onAction(ReaderAction.OpenNextChapter)
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = state.chapterTitle.ifEmpty { "阅读器" },
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
                    TextButton(onClick = onBack) { Text("返回") }
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
                        text = "无法加载此章节。",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Button(onClick = { onAction(ReaderAction.Retry) }) { Text("重试") }
                }

                ReaderStatus.Ready -> PageContent(
                    state = state,
                    onAction = onAction,
                    decoder = decoder,
                    onChapterEndChange = { chapterEndReached = it },
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
    onChapterEndChange: (Boolean) -> Unit,
) {
    if (state.pageCount == 0) {
        Text(
            text = "此章节没有页面。",
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
                onChapterEndChange = onChapterEndChange,
                onAction = onAction,
            )

            ReadingDirection.LeftToRight,
            ReadingDirection.RightToLeft,
            -> SinglePagePager(
                state = state,
                viewport = viewport,
                decoder = decoder,
                onChapterEndChange = onChapterEndChange,
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
    onChapterEndChange: (Boolean) -> Unit,
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
        var userScrollStarted = false
        snapshotFlow {
            Triple(listState.firstVisibleItemIndex, listState.isScrollInProgress,
                listState.layoutInfo.visibleItemsInfo.isNotEmpty())
        }.collect { (first, scrolling, hasVisibleItems) ->
            if (scrolling) userScrollStarted = true
            // Layout may clamp the restored last page below a taller previous page. Only an
            // actual user scroll may replace the progress restored by ReaderViewModel.
            if (!userScrollStarted || !hasVisibleItems) return@collect
            val page = if (!listState.canScrollForward && first > 0) {
                items.lastOrNull()?.page?.index
            } else {
                items.getOrNull(first)?.page?.index
            }
            page?.let { onAction(ReaderAction.PageShown(it)) }
        }
    }
    val lastPageReady = state.pages.lastOrNull()?.sizeState == PageSizeState.Ready
    LaunchedEffect(listState, items.size, lastPageReady) {
        snapshotFlow {
            val visibleLast = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
            items.isNotEmpty() && listState.layoutInfo.totalItemsCount > 0 &&
                visibleLast == items.lastIndex && !listState.canScrollForward && !zoomState.isZoomed &&
                lastPageReady
        }.distinctUntilChanged().collect(onChapterEndChange)
    }
    val contentWidthPx = items.maxOfOrNull { it.tile.displayWidthPx }?.toFloat() ?: viewport.widthPx.toFloat()
    val contentHeightPx = items.sumOf { it.tile.displayHeightPx }.toFloat()
    LaunchedEffect(listState, items, viewport, decoder) {
        if (decoder == null) return@LaunchedEffect
        snapshotFlow { items.getOrNull(listState.firstVisibleItemIndex)?.page?.index }
            .distinctUntilChanged()
            .collectLatest { index ->
                if (index != null) predecodeAdjacentPages(index, state.pages, viewport, decoder)
            }
    }
    val transformState = rememberTransformableState { _, zoomFactor, pan, _ ->
        zoomState.applyGesture(
            pan = pan,
            zoomFactor = zoomFactor,
            viewport = viewport,
            contentWidthPx = contentWidthPx,
            contentHeightPx = contentHeightPx,
        )
    }
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                translationX = zoomState.offsetX
                translationY = zoomState.offsetY
            }
            .transformable(
                state = transformState,
                canPan = { zoomState.isZoomed },
                lockRotationOnZoomPan = true,
            ),
        userScrollEnabled = true,
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        items(items, key = { "${it.page.index}:${it.tileIndex}" }) { item ->
            PageTile(
                item = item,
                decoder = decoder,
                modifier = Modifier.padding(vertical = 4.dp),
                onRetry = { onAction(ReaderAction.RetryPage(item.page.index)) },
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
    onChapterEndChange: (Boolean) -> Unit,
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
    LaunchedEffect(pagerState.currentPage) { onChapterEndChange(false) }
    LaunchedEffect(pagerState, state.pages, viewport, decoder) {
        if (decoder == null) return@LaunchedEffect
        snapshotFlow { pagerState.currentPage }
            .distinctUntilChanged()
            .collectLatest { currentPage ->
                predecodeAdjacentPages(currentPage, state.pages, viewport, decoder)
            }
    }
    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
        reverseLayout = state.direction == ReadingDirection.RightToLeft,
    ) { index ->
        PagedPage(
            page = state.pages[index],
            viewport = viewport,
            decoder = decoder,
            isLastChapterPage = index == state.pageCount - 1,
            onChapterEndChange = { reached ->
                if (index == pagerState.currentPage) onChapterEndChange(reached)
            },
            onRetry = { onAction(ReaderAction.RetryPage(index)) },
        )
    }
}

/**
 * Wait for active scrolling/flinging to settle before using decode time on adjacent pages. Keeping
 * the work inside collectLatest also cancels stale page decodes when the user moves on quickly.
 */
private suspend fun predecodeAdjacentPages(
    currentPage: Int,
    pages: List<ComicPage>,
    viewport: PageViewport,
    decoder: PageImageDecoder,
) {
    delay(PAGE_PREDECODE_IDLE_MILLIS)
    coroutineScope {
        listOf(currentPage - 1, currentPage + 1)
            .filter { it in pages.indices && pages[it].sizeState == PageSizeState.Ready }
            .forEach { index ->
                launch {
                    val page = pages[index]
                    try {
                        val tile = decoder.plan(page, viewport, zoom = 1f, continuous = true).firstOrNull()
                            ?: return@launch
                        decoder.predecode(
                            PageDecodeRequest(
                                path = page.imageRef,
                                sourceId = page.sourceId,
                                targetWidthPx = tile.displayWidthPx,
                                targetHeightPx = tile.displayHeightPx,
                                region = tile.region,
                                fitWidthOnly = tile.fitWidthOnly,
                            ),
                        )
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // The page's visible tile owns its normal retry and error state.
                    }
                }
            }
    }
}

/** A horizontally paged comic page stays fitted to the screen width and scrolls vertically. */
@Composable
private fun PagedPage(
    page: ComicPage,
    viewport: PageViewport,
    decoder: PageImageDecoder?,
    isLastChapterPage: Boolean,
    onChapterEndChange: (Boolean) -> Unit,
    onRetry: () -> Unit = {},
) {
    val zoomState = rememberReaderZoomState()
    val tiles = remember(page, viewport, decoder, zoomState.scale) {
        decoder?.plan(page, viewport, zoom = zoomState.scale, continuous = true)
            ?: listOf(placeholderTile(page, viewport, continuous = true))
    }
    val listState = rememberLazyListState()
    val pageReady = page.sizeState == PageSizeState.Ready
    LaunchedEffect(listState, tiles.size, isLastChapterPage, pageReady) {
        if (!isLastChapterPage) return@LaunchedEffect
        snapshotFlow {
            val visibleLast = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
            tiles.isNotEmpty() && listState.layoutInfo.totalItemsCount > 0 &&
                visibleLast == tiles.lastIndex && !listState.canScrollForward && !zoomState.isZoomed &&
                pageReady
        }.distinctUntilChanged().collect(onChapterEndChange)
    }
    val contentWidthPx = PageTiling.fitWidthScale(page.widthPx, viewport.widthPx) * page.widthPx * zoomState.scale
    val contentHeightPx = PageTiling.fitWidthScale(page.widthPx, viewport.widthPx) * page.heightPx * zoomState.scale
    val transformState = rememberTransformableState { _, zoomFactor, pan, _ ->
        zoomState.applyGesture(
            pan = pan,
            zoomFactor = zoomFactor,
            viewport = viewport,
            contentWidthPx = contentWidthPx,
            contentHeightPx = contentHeightPx,
        )
    }
    LaunchedEffect(zoomState.isZoomed) {
        if (zoomState.isZoomed) listState.scrollToItem(0)
    }
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                translationX = zoomState.offsetX
                translationY = zoomState.offsetY
            }
            .transformable(
                state = transformState,
                canPan = { zoomState.isZoomed },
                lockRotationOnZoomPan = true,
            ),
        userScrollEnabled = !zoomState.isZoomed,
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        items(tiles.size, key = { "${page.index}:$it" }) { tileIndex ->
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                PageTile(
                    item = TileItem(page, tileIndex, tiles[tileIndex]),
                    decoder = decoder,
                    onRetry = onRetry,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
        }
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
    onRetry: () -> Unit = {},
) {
    val density = LocalDensity.current
    val tile = item.tile
    val boxModifier = with(density) {
        modifier.requiredSize(width = tile.displayWidthPx.toDp(), height = tile.displayHeightPx.toDp())
    }

    if (decoder == null) {
        Box(
            modifier = boxModifier.background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "第 ${item.page.index + 1} 页",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    if (item.page.sizeState != dev.veneranative.core.model.PageSizeState.Ready) {
        Box(modifier = boxModifier, contentAlignment = Alignment.Center) {
            if (item.page.sizeState == dev.veneranative.core.model.PageSizeState.Failed) {
                Button(onClick = onRetry) { Text("重试第 ${item.page.index + 1} 页") }
            } else CircularProgressIndicator()
        }
        return
    }
    var retryGeneration by remember(item.page.imageRef) { mutableStateOf(0) }
    val imageState by produceState<PageImageState?>(initialValue = null, item.page, tile, decoder, retryGeneration) {
        value = null
        value = try {
            val decoded = decoder.decode(
                PageDecodeRequest(
                    path = item.page.imageRef,
                    sourceId = item.page.sourceId,
                    targetWidthPx = tile.displayWidthPx,
                    targetHeightPx = tile.displayHeightPx,
                    region = tile.region,
                    fitWidthOnly = tile.fitWidthOnly,
                ),
            )
            PageImageState.Decoded(decoded)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: OutOfMemoryError) {
            PageImageState.OutOfMemory
        } catch (_: Exception) {
            PageImageState.Failed
        }
    }

    Box(modifier = boxModifier, contentAlignment = Alignment.Center) {
        when (val current = imageState) {
            null -> CircularProgressIndicator()

            PageImageState.Failed -> Button(onClick = { retryGeneration++ }) { Text("重试第 ${item.page.index + 1} 页") }

            PageImageState.OutOfMemory -> Text(
                text = "图片过大，无法解码。",
                style = MaterialTheme.typography.bodyMedium,
            )

            is PageImageState.Decoded -> FittedPageImage(
                bitmap = current.image.bitmap.asImageBitmap(),
                contentDescription = "第 ${item.page.index + 1} 页",
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
internal fun FittedPageImage(
    bitmap: androidx.compose.ui.graphics.ImageBitmap,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    Image(
        bitmap = bitmap,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = ContentScale.Fit,
    )
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

private const val PAGE_PREDECODE_IDLE_MILLIS = 350L

private fun ReadingDirection.label(): String = when (this) {
    ReadingDirection.Vertical -> "竖向"
    ReadingDirection.LeftToRight -> "从左到右"
    ReadingDirection.RightToLeft -> "从右到左"
}

private fun DecodeStrategy.label(): String = when (this) {
    DecodeStrategy.Sampled -> "采样解码"
    DecodeStrategy.Region -> "区域解码"
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
