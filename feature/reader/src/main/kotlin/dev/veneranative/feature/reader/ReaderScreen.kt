package dev.veneranative.feature.reader

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
import android.app.Activity
import android.content.Context
import kotlin.math.roundToInt
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo


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
    var controlsVisible by rememberSaveable { mutableStateOf(false) }
    var sliderPage by remember(state.currentPageNumber) { mutableStateOf(state.currentPageNumber.toFloat()) }
    val view = LocalView.current
    val window = remember(view) { view.context.findActivity()?.window }
    var chapterEndReached by remember(state.chapterTitle) { mutableStateOf(false) }
    val decoder: PageImageDecoder? = decoderFactory?.let { factory ->
        remember(factory, strategy) { factory(strategy) }
    }
    DisposableEffect(decoder) {
        onDispose { decoder?.close() }
    }
    SideEffect {
        window?.let { target ->
            val controller = WindowCompat.getInsetsController(target, target.decorView)
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.navigationBars())
            if (controlsVisible && state.status == ReaderStatus.Ready) {
                controller.show(WindowInsetsCompat.Type.statusBars())
            } else {
                controller.hide(WindowInsetsCompat.Type.statusBars())
            }
        }
    }
    DisposableEffect(window) {
        onDispose {
            window?.let { WindowCompat.getInsetsController(it, it.decorView).show(WindowInsetsCompat.Type.systemBars()) }
        }
    }
    LaunchedEffect(state.pages.size) {
        chapterEndReached = false
    }
    LaunchedEffect(chapterEndReached) {
        if (chapterEndReached) {
            onAction(ReaderAction.LoadNextChapter)
        }
    }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier.fillMaxSize().testTag("reader-canvas").pointerInput(state.status) {
                detectTapGestures { tap ->
                    val width = size.width.toFloat()
                    val height = size.height.toFloat()
                    if (tap.x in width * 0.28f..width * 0.72f && tap.y in height * 0.25f..height * 0.75f) {
                        controlsVisible = !controlsVisible
                    }
                }
            },
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
        val showControls = controlsVisible && state.status == ReaderStatus.Ready
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(tween(180)) + slideInVertically(
                animationSpec = tween(260, easing = FastOutSlowInEasing),
                initialOffsetY = { it / 5 },
            ),
            exit = fadeOut(tween(130)) + slideOutVertically(
                animationSpec = tween(200, easing = FastOutSlowInEasing),
                targetOffsetY = { it / 5 },
            ),
            modifier = Modifier.fillMaxSize(),
        ) {
            ReaderToolOverlay(
                state = state,
                sliderPage = sliderPage,
                onSliderPageChange = { sliderPage = it },
                onSeek = { onAction(ReaderAction.SeekPage(it - 1)) },
                onPreviousChapter = { onAction(ReaderAction.LoadPreviousChapter) },
                onNextChapter = { onAction(ReaderAction.NavigateNextChapter) },
                onRetryNextChapter = { onAction(ReaderAction.RetryNextChapter) },
                onRetryPreviousChapter = { onAction(ReaderAction.RetryPreviousChapter) },
                strategy = if (decoderFactory == null) null else strategy,
                onDirectionChange = { onAction(ReaderAction.ChangeDirection(it)) },
                onStrategyChange = { strategy = it },
            )
        }
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(tween(180)) + slideInVertically(
                animationSpec = tween(260, easing = FastOutSlowInEasing),
                initialOffsetY = { -it },
            ),
            exit = fadeOut(tween(120)) + slideOutVertically(
                animationSpec = tween(180, easing = FastOutSlowInEasing),
                targetOffsetY = { -it },
            ),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            ReaderTopBar(state = state, onBack = onBack)
        }
    }
}

@Composable
private fun ReaderToolOverlay(
    state: ReaderUiState,
    sliderPage: Float,
    onSliderPageChange: (Float) -> Unit,
    onSeek: (Int) -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onRetryNextChapter: () -> Unit,
    onRetryPreviousChapter: () -> Unit,
    strategy: DecodeStrategy?,
    onDirectionChange: (ReadingDirection) -> Unit,
    onStrategyChange: (DecodeStrategy) -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 4.dp,
            shadowElevation = 8.dp,
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                if (state.isLoadingNextChapter || state.isLoadingPreviousChapter) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("正在加载章节…", modifier = Modifier.padding(start = 8.dp))
                    }
                } else if (state.nextChapterLoadFailed || state.previousChapterLoadFailed) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("章节加载失败", modifier = Modifier.weight(1f))
                        if (state.nextChapterLoadFailed) TextButton(onClick = onRetryNextChapter) { Text("重试") }
                        else TextButton(onClick = onRetryPreviousChapter) { Text("重试") }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = onPreviousChapter,
                        enabled = state.previousChapter != null && !state.isLoadingPreviousChapter,
                        modifier = Modifier.semantics { contentDescription = "上一话" },
                    ) { ChapterStepIcon(previous = true) }
                    ReaderPageSeekBar(
                        currentPage = sliderPage.roundToInt().coerceIn(1, state.currentChapterPageCount.coerceAtLeast(1)),
                        pageCount = state.currentChapterPageCount,
                        onPagePreview = { onSliderPageChange(it.toFloat()) },
                        onPageSelected = onSeek,
                        modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                    )
                    IconButton(
                        onClick = onNextChapter,
                        enabled = state.nextChapter != null && !state.isLoadingNextChapter,
                        modifier = Modifier.semantics { contentDescription = "下一话" },
                    ) { ChapterStepIcon(previous = false) }
                }
                Text(
                    "${sliderPage.roundToInt().coerceAtLeast(1)} / ${state.currentChapterPageCount} 页",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                Spacer(modifier = Modifier.size(8.dp))
                ReaderControls(
                    direction = state.direction,
                    strategy = strategy,
                    onDirectionChange = onDirectionChange,
                    onStrategyChange = onStrategyChange,
                )
            }
        }
    }
}

@Composable
private fun ReaderTopBar(
    state: ReaderUiState,
    onBack: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .height(56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Text("‹", style = MaterialTheme.typography.headlineMedium) }
            Column(modifier = Modifier.weight(1f)) {
                Text(state.chapterTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${state.currentPageNumber} / ${state.currentChapterPageCount}", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun ReaderPageSeekBar(
    currentPage: Int,
    pageCount: Int,
    onPagePreview: (Int) -> Unit,
    onPageSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val lastIndex = (pageCount - 1).coerceAtLeast(0)
    BoxWithConstraints(modifier = modifier.height(42.dp)) {
        val density = LocalDensity.current
        val horizontalInsetPx = with(density) { 10.dp.toPx() }
        val trackWidthPx = with(density) { (maxWidth - 20.dp).toPx() }.coerceAtLeast(1f)
        val pageStepPx = trackWidthPx / lastIndex.coerceAtLeast(1)
        var dragPageIndex by remember(pageCount) {
            mutableFloatStateOf((currentPage - 1).coerceIn(0, lastIndex).toFloat())
        }
        val dragState = rememberDraggableState { delta ->
            if (lastIndex > 0) {
                dragPageIndex = (dragPageIndex + delta / pageStepPx).coerceIn(0f, lastIndex.toFloat())
                onPagePreview(dragPageIndex.roundToInt() + 1)
            }
        }
        val colors = MaterialTheme.colorScheme

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .semantics {
                    if (lastIndex > 0) {
                        progressBarRangeInfo = ProgressBarRangeInfo(
                            currentPage.toFloat() - 1f,
                            0f..lastIndex.toFloat(),
                        )
                    }
                }
                .draggable(
                    state = dragState,
                    orientation = Orientation.Horizontal,
                    enabled = lastIndex > 0,
                    onDragStarted = { position ->
                        dragPageIndex = (((position.x - horizontalInsetPx) / trackWidthPx).coerceIn(0f, 1f) * lastIndex)
                        onPagePreview(dragPageIndex.roundToInt() + 1)
                    },
                    onDragStopped = {
                        onPageSelected(dragPageIndex.roundToInt() + 1)
                    },
                )
                .pointerInput(lastIndex, trackWidthPx) {
                    detectTapGestures { position ->
                        if (lastIndex > 0) {
                            val target = (((position.x - horizontalInsetPx) / trackWidthPx).coerceIn(0f, 1f) * lastIndex)
                                .roundToInt() + 1
                            dragPageIndex = (target - 1).toFloat()
                            onPagePreview(target)
                            onPageSelected(target)
                        }
                    }
                },
        ) {
            val startX = horizontalInsetPx
            val endX = size.width - horizontalInsetPx
            val centerY = size.height / 2f
            val progress = if (lastIndex == 0) 0f else ((currentPage - 1f) / lastIndex).coerceIn(0f, 1f)
            val thumbX = startX + (endX - startX) * progress
            drawLine(
                color = colors.surfaceVariant,
                start = Offset(startX, centerY),
                end = Offset(endX, centerY),
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round,
            )
            if (progress > 0f) {
                drawLine(
                    color = colors.primary,
                    start = Offset(startX, centerY),
                    end = Offset(thumbX, centerY),
                    strokeWidth = 3.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
            drawCircle(colors.surface, radius = 8.dp.toPx(), center = Offset(thumbX, centerY))
            drawCircle(colors.primary, radius = 5.dp.toPx(), center = Offset(thumbX, centerY))
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
    LaunchedEffect(state.currentPageIndex, items) {
        val targetItem = items.indexOfFirst { it.page.index == state.currentPageIndex }.coerceAtLeast(0)
        if (targetItem != listState.firstVisibleItemIndex) listState.scrollToItem(targetItem)
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
    LaunchedEffect(state.currentPageIndex) {
        if (state.currentPageIndex in 0 until pagerState.pageCount && pagerState.currentPage != state.currentPageIndex) {
            pagerState.animateScrollToPage(state.currentPageIndex)
        }
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
                                pageIndex = page.index,
                                region = tile.region,
                                fitWidthOnly = tile.fitWidthOnly,
                                headers = page.imageHeaders,
                                reverseHorizontalBands = page.reverseHorizontalBands,
                                sourceImageHeightPx = page.heightPx,
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
                    pageIndex = item.page.index,
                    region = tile.region,
                    fitWidthOnly = tile.fitWidthOnly,
                    headers = item.page.imageHeaders,
                    reverseHorizontalBands = item.page.reverseHorizontalBands,
                    sourceImageHeightPx = item.page.heightPx,
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
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("翻页方式", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ReadingDirection.entries.forEach { candidate ->
                FilterChip(
                    selected = candidate == direction,
                    onClick = { onDirectionChange(candidate) },
                    label = {
                        Text(candidate.label(), maxLines = 1, style = MaterialTheme.typography.labelMedium)
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (strategy != null) {
            Text("图片解码", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DecodeStrategy.entries.forEach { candidate ->
                    FilterChip(
                        selected = candidate == strategy,
                        onClick = { onStrategyChange(candidate) },
                        label = {
                            Text(candidate.label(), maxLines = 1, style = MaterialTheme.typography.labelMedium)
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun ChapterStepIcon(previous: Boolean) {
    val color = LocalContentColor.current
    Canvas(Modifier.size(22.dp)) {
        val strokeWidth = 2.dp.toPx()
        val x = if (previous) size.width * .28f else size.width * .72f
        drawLine(
            color = color,
            start = Offset(x, size.height * .2f),
            end = Offset(x, size.height * .8f),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
        val chevron = Path().apply {
            if (previous) {
                moveTo(size.width * .72f, size.height * .2f)
                lineTo(size.width * .42f, size.height * .5f)
                lineTo(size.width * .72f, size.height * .8f)
            } else {
                moveTo(size.width * .28f, size.height * .2f)
                lineTo(size.width * .58f, size.height * .5f)
                lineTo(size.width * .28f, size.height * .8f)
            }
        }
        drawPath(chevron, color, style = Stroke(width = strokeWidth, cap = StrokeCap.Round))
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
