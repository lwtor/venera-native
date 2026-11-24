package dev.veneranative.feature.reader

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.DpSize
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import android.app.Activity
import android.content.Context
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.snap
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo

private class ReaderDoubleTapZoomHandler {
    var onDoubleTap: ((Offset) -> Unit)? = null
    var onTransformStart: ((Offset) -> Unit)? = null
    var onTransform: ((Offset, Float, Offset) -> Unit)? = null
    var onPan: ((Offset, Offset) -> Boolean)? = null
    var onGestureEnd: (() -> Unit)? = null
}

/** Handles taps, pinch, and zoomed panning before child scrollables can claim the pointers. */
private suspend fun PointerInputScope.detectReaderTapGestures(
    handler: ReaderDoubleTapZoomHandler,
    onTap: (Offset) -> Unit,
) {
    coroutineScope {
        var pendingTap: Pair<Offset, Long>? = null
        var pendingTapJob: Job? = null
        val doubleTapWindowMillis = viewConfiguration.doubleTapTimeoutMillis + DOUBLE_TAP_EVENT_GRACE_MILLIS
        val singleTapSettleMillis = doubleTapWindowMillis + SINGLE_TAP_CALLBACK_GRACE_MILLIS
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val firstTapCandidate = pendingTap?.takeIf { (position, releasedAt) ->
                down.uptimeMillis - releasedAt in 0..singleTapSettleMillis &&
                    (down.position - position).getDistance() <= viewConfiguration.touchSlop
            }
            val isDoubleTapCandidate = firstTapCandidate != null &&
                down.uptimeMillis - firstTapCandidate.second <= doubleTapWindowMillis
            if (firstTapCandidate != null) {
                // A valid second down owns the pending first tap. Do not let its single-tap
                // timeout show the controls while the second tap is still being released.
                pendingTapJob?.cancel()
                pendingTapJob = null
            }
            var canceled = false
            var transforming = false
            var panning = false
            var lastPosition = down.position
            var upPosition: Offset? = null
            var upTime = 0L
            var previousPositions = mapOf(down.id to down.position)
            while (!canceled && upPosition == null) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val activePointers = event.changes.filter { it.pressed }
                if (activePointers.size >= 2) {
                    if (!transforming) {
                        pendingTapJob?.cancel()
                        pendingTapJob = null
                        if (firstTapCandidate == null) pendingTap = null
                        handler.onTransformStart?.invoke(averagePosition(activePointers.map { it.position }))
                        transforming = true
                    }
                    val comparable = activePointers.filter { it.id in previousPositions }
                    if (comparable.size >= 2) {
                        val previous = comparable.map { previousPositions.getValue(it.id) }
                        val current = comparable.map { it.position }
                        val previousCentroid = averagePosition(previous)
                        val currentCentroid = averagePosition(current)
                        val previousSpan = averageDistance(previous, previousCentroid)
                        val currentSpan = averageDistance(current, currentCentroid)
                        val zoomFactor = if (previousSpan > 0f) currentSpan / previousSpan else 1f
                        handler.onTransform?.invoke(currentCentroid, zoomFactor, currentCentroid - previousCentroid)
                    }
                    event.changes.forEach { it.consume() }
                    previousPositions = activePointers.associate { it.id to it.position }
                    continue
                }
                if (transforming) {
                    event.changes.forEach { it.consume() }
                    if (activePointers.isEmpty()) {
                        handler.onGestureEnd?.invoke()
                        canceled = true
                    }
                    previousPositions = activePointers.associate { it.id to it.position }
                    continue
                }
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                val delta = change.position - lastPosition
                val distanceFromDown = change.position - down.position
                if (change.pressed && (panning || distanceFromDown.getDistance() > viewConfiguration.touchSlop)) {
                    if (handler.onPan?.invoke(change.position, if (panning) delta else distanceFromDown) == true) {
                        panning = true
                        change.consume()
                        lastPosition = change.position
                        previousPositions = mapOf(down.id to change.position)
                        continue
                    }
                    canceled = true
                    break
                }
                if (panning && change.pressed) {
                    change.consume()
                    lastPosition = change.position
                    previousPositions = mapOf(down.id to change.position)
                    continue
                }
                if (!change.pressed) {
                    upPosition = change.position
                    upTime = change.uptimeMillis
                } else {
                    previousPositions = mapOf(down.id to change.position)
                }
            }
            if (panning) {
                handler.onGestureEnd?.invoke()
                canceled = true
            }
            val releasedAt = upPosition
            if (!canceled && releasedAt != null) {
                val previousTap = pendingTap
                val isDoubleTap = isDoubleTapCandidate && previousTap != null &&
                    (releasedAt - previousTap.first).getDistance() <= viewConfiguration.touchSlop
                if (isDoubleTap) {
                    pendingTapJob?.cancel()
                    pendingTapJob = null
                    pendingTap = null
                    handler.onDoubleTap?.invoke(releasedAt)
                } else {
                    if (firstTapCandidate != null) {
                        pendingTap = null
                        onTap(firstTapCandidate.first)
                    }
                    pendingTapJob?.cancel()
                    pendingTap = releasedAt to upTime
                    val tapTime = upTime
                    pendingTapJob = launch {
                        delay(singleTapSettleMillis)
                        if (pendingTap?.second == tapTime) {
                            pendingTap = null
                            onTap(releasedAt)
                        }
                    }
                }
            } else if (firstTapCandidate != null) {
                pendingTap = null
                onTap(firstTapCandidate.first)
            }
        }
    }
}

private fun averagePosition(points: List<Offset>): Offset =
    points.fold(Offset.Zero, Offset::plus) / points.size.coerceAtLeast(1).toFloat()

private fun averageDistance(points: List<Offset>, center: Offset): Float =
    points.sumOf { (it - center).getDistance().toDouble() }.toFloat() / points.size.coerceAtLeast(1).toFloat()

private fun preferredNextPage(currentPage: Int, direction: ReadingDirection): Int =
    currentPage + if (direction == ReadingDirection.RightToLeft) -1 else 1

private const val DOUBLE_TAP_EVENT_GRACE_MILLIS = 120L
private const val SINGLE_TAP_CALLBACK_GRACE_MILLIS = 80L
private const val TOOLBAR_LONG_PRESS_MILLIS = 1_000L


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
    onOpenComicDetails: (() -> Unit)? = null,
) {
    var strategy by rememberSaveable { mutableStateOf(DecodeStrategy.Region) }
    val controlsScope = rememberCoroutineScope()
    val controlsTimer = remember(controlsScope) { ReaderControlsTimer(controlsScope) }
    val controlsVisible by controlsTimer.visible.collectAsState()
    fun toolbarAction(action: ReaderAction) {
        controlsTimer.onInteraction()
        onAction(action)
    }
    LaunchedEffect(state.status) {
        if (state.status != ReaderStatus.Ready) controlsTimer.hide()
    }
    val doubleTapZoomHandler = remember { ReaderDoubleTapZoomHandler() }
    var sliderPage by remember(state.currentPageNumber) { mutableStateOf(state.currentPageNumber.toFloat()) }
    val view = LocalView.current
    val window = remember(view) { view.context.findActivity()?.window }
    var chapterEndReached by remember(state.chapterTitle) { mutableStateOf(false) }
    var splitMenuSourceIndex by remember { mutableStateOf<Int?>(null) }
    val pageHitTester = remember { PageHitTester() }
    // The tap gesture only restarts on a status change, so it must not close over [state]: doing so
    // would freeze the first Ready state's pages, page index and split flag for the rest of the
    // session. The key stays status-only, otherwise restarting would cancel in-flight gestures.
    val latestState = rememberUpdatedState(state)
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

    Box(modifier = modifier.fillMaxSize().pointerInput(controlsTimer) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                controlsTimer.onPointerActivity(event.changes.any { it.pressed })
            }
        }
    }, contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier.fillMaxSize().testTag("reader-canvas")
                .pointerInput(state.status) {
                    detectTapGestures(onPress = { tap ->
                        val releaseResult = withTimeoutOrNull(TOOLBAR_LONG_PRESS_MILLIS) {
                            tryAwaitRelease()
                        }
                        val heldForOneSecond = releaseResult == null
                        val wasTap = releaseResult == true
                        val width = size.width.toFloat()
                        val height = size.height.toFloat()
                        val current = latestState.value
                        if (current.status == ReaderStatus.Ready) {
                            val inToolbarTarget = ReaderControlsTapTarget.contains(tap.x, tap.y, width, height)
                            when {
                                controlsTimer.visible.value && wasTap -> controlsTimer.hide()
                                !controlsTimer.visible.value && heldForOneSecond && inToolbarTarget ->
                                    controlsTimer.toggle()
                                !controlsTimer.visible.value && heldForOneSecond && current.splitEnabled &&
                                    current.pages.isNotEmpty() -> {
                                    val fallback = current.pages
                                        .getOrNull(current.currentPageIndex.coerceIn(0, current.pages.lastIndex))
                                        ?.sourceIndex ?: 0
                                    splitMenuSourceIndex = pageHitTester.resolve?.invoke(tap.y) ?: fallback
                                    controlsTimer.hide()
                                }
                            }
                        }
                    })
                }
                .pointerInput(state.status) {
                detectReaderTapGestures(
                    handler = doubleTapZoomHandler,
                    onTap = { /* A short center tap remains available to normal reader gestures. */ },
                )
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
                    // Naming the layer that failed beats a generic line: "Invalid status code: 210"
                    // means throttling, an empty payload means a different bug entirely.
                    state.failureDetail?.let { detail ->
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .padding(horizontal = 32.dp)
                                .testTag("reader-failure-detail"),
                        )
                    }
                    Button(onClick = { onAction(ReaderAction.Retry) }) { Text("重试") }
                }

                ReaderStatus.Ready -> PageContent(
                    state = state,
                    onAction = onAction,
                    decoder = decoder,
                    pageHitTester = pageHitTester,
                    doubleTapZoomHandler = doubleTapZoomHandler,
                    onChapterEndChange = { chapterEndReached = it },
                    onChapterStartReached = {
                        onAction(
                            if (state.direction == ReadingDirection.Vertical) {
                                ReaderAction.AutoLoadPreviousChapter
                            } else {
                                ReaderAction.LoadPreviousChapter
                            },
                        )
                    },
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
                onSliderPageChange = { controlsTimer.onInteraction(); sliderPage = it },
                onSeek = { toolbarAction(ReaderAction.SeekPage(it - 1)) },
                onPreviousChapter = { toolbarAction(ReaderAction.LoadPreviousChapter) },
                onNextChapter = { toolbarAction(ReaderAction.NavigateNextChapter) },
                onRetryNextChapter = { toolbarAction(ReaderAction.RetryNextChapter) },
                onRetryPreviousChapter = { toolbarAction(ReaderAction.RetryPreviousChapter) },
                strategy = if (decoderFactory == null) null else strategy,
                onDirectionChange = { toolbarAction(ReaderAction.ChangeDirection(it)) },
                onStrategyChange = { controlsTimer.onInteraction(); strategy = it },
                onSplitChange = { toolbarAction(ReaderAction.ToggleSplit) },
                onSplitOverlapChange = { toolbarAction(ReaderAction.SetSplitOverlap(it)) },
                onSplitOrderChange = { toolbarAction(ReaderAction.SetSplitOrder(it)) },
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
            ReaderTopBar(state = state, onBack = onBack, onOpenComicDetails = onOpenComicDetails)
        }
        if (!showControls && state.status == ReaderStatus.Ready && state.endOfChaptersReached) {
            EndOfChaptersNotice(modifier = Modifier.align(Alignment.BottomCenter))
        }
        if (!showControls && state.status == ReaderStatus.Ready && state.nextChapterUnknown) {
            UnknownNextChapterNotice(
                onRetry = { onAction(ReaderAction.RetryNextChapter) },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
        if (!showControls && state.status == ReaderStatus.Ready && (state.nextChapterLoadFailed || state.previousChapterLoadFailed)) {
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp).testTag("reader-next-chapter-error"),
                shape = MaterialTheme.shapes.large,
                tonalElevation = 4.dp,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 8.dp)) {
                    Text(if (state.nextChapterLoadFailed) "下一话加载失败" else "上一话加载失败")
                    TextButton(onClick = {
                        onAction(if (state.nextChapterLoadFailed) ReaderAction.RetryNextChapter else ReaderAction.RetryPreviousChapter)
                    }) { Text("重试") }
                }
            }
        }
        splitMenuSourceIndex?.let { sourceIndex ->
            val excluded = sourceIndex in state.excludedPages
            AlertDialog(
                onDismissRequest = { splitMenuSourceIndex = null },
                title = { Text("第 ${sourceIndex + 1} 页") },
                text = { Text(if (excluded) "此页已设为不拆分。" else "此页为跨页，当前会自动拆分。") },
                confirmButton = {
                    TextButton(onClick = {
                        onAction(ReaderAction.SetPageSplit(sourceIndex, split = excluded))
                        splitMenuSourceIndex = null
                    }) { Text(if (excluded) "恢复拆分" else "本页不拆分") }
                },
                dismissButton = {
                    TextButton(onClick = { splitMenuSourceIndex = null }) { Text("取消") }
                },
            )
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
    onSplitChange: () -> Unit,
    onSplitOverlapChange: (Float) -> Unit,
    onSplitOrderChange: (Boolean) -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)
                .testTag("reader-tool-overlay"),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 4.dp,
            shadowElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
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
                    splitEnabled = state.splitEnabled,
                    splitOverlap = state.splitOverlapFraction,
                    splitRightToLeft = state.splitRightToLeft,
                    onDirectionChange = onDirectionChange,
                    onStrategyChange = onStrategyChange,
                    onSplitChange = onSplitChange,
                    onSplitOverlapChange = onSplitOverlapChange,
                    onSplitOrderChange = onSplitOrderChange,
                )
            }
        }
    }
}

@Composable
private fun ReaderTopBar(
    state: ReaderUiState,
    onBack: () -> Unit,
    onOpenComicDetails: (() -> Unit)?,
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
            if (onOpenComicDetails != null) {
                IconButton(
                    onClick = onOpenComicDetails,
                    modifier = Modifier.testTag("reader-open-details").semantics { contentDescription = "漫画详情" },
                ) {
                    Box(
                        modifier = Modifier.size(32.dp).border(1.dp, LocalContentColor.current, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { Text("详", style = MaterialTheme.typography.labelLarge) }
                }
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

/**
 * Resolves the source page sitting under a vertical canvas coordinate.
 *
 * The reader's "current page" is the first visible item of the scrolling canvas, and it collapses
 * to the last page near the chapter end, so it cannot answer "which page did the user touch".
 * [ContinuousPages] installs the real implementation; paged mode falls back to the visible page.
 */
private class PageHitTester {
    var resolve: ((y: Float) -> Int?)? = null
}

@Composable
private fun PageContent(
    state: ReaderUiState,
    onAction: (ReaderAction) -> Unit,
    decoder: PageImageDecoder?,
    pageHitTester: PageHitTester,
    doubleTapZoomHandler: ReaderDoubleTapZoomHandler,
    onChapterEndChange: (Boolean) -> Unit,
    onChapterStartReached: () -> Unit,
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
                pageHitTester = pageHitTester,
                onChapterEndChange = onChapterEndChange,
                onChapterStartReached = onChapterStartReached,
                onAction = onAction,
                doubleTapZoomHandler = doubleTapZoomHandler,
            )

            ReadingDirection.LeftToRight,
            ReadingDirection.RightToLeft,
            -> SinglePagePager(
                state = state,
                viewport = viewport,
                decoder = decoder,
                pageHitTester = pageHitTester,
                onChapterEndChange = onChapterEndChange,
                onChapterStartReached = onChapterStartReached,
                onAction = onAction,
                doubleTapZoomHandler = doubleTapZoomHandler,
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
    pageHitTester: PageHitTester,
    onChapterEndChange: (Boolean) -> Unit,
    onChapterStartReached: () -> Unit,
    onAction: (ReaderAction) -> Unit,
    doubleTapZoomHandler: ReaderDoubleTapZoomHandler,
) {
    val zoomState = rememberReaderZoomState()
    val zoomAnimationScope = rememberCoroutineScope()
    var animateZoom by remember { mutableStateOf(false) }
    var zoomAnimationJob by remember { mutableStateOf<Job?>(null) }
    val renderedScale by animateFloatAsState(
        targetValue = zoomState.scale,
        animationSpec = if (animateZoom) tween(260, easing = FastOutSlowInEasing) else snap(),
        label = "reader-zoom-scale",
    )
    val renderedOffsetX by animateFloatAsState(
        targetValue = zoomState.offsetX,
        animationSpec = if (animateZoom) tween(260, easing = FastOutSlowInEasing) else snap(),
        label = "reader-zoom-offset-x",
    )
    val renderedOffsetY by animateFloatAsState(
        targetValue = zoomState.offsetY,
        animationSpec = if (animateZoom) tween(260, easing = FastOutSlowInEasing) else snap(),
        label = "reader-zoom-offset-y",
    )
    val items = remember(state.pages, viewport, decoder) {
        state.pages.flatMap { page ->
            val tiles = decoder?.plan(page, viewport, zoom = 1f, continuous = true)
                ?: listOf(placeholderTile(page, viewport, continuous = true))
            tiles.mapIndexed { index, tile -> TileItem(page, index, tile) }
        }
    }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = items.indexOfFirst { it.page.index == state.currentPageIndex }.coerceAtLeast(0),
    )
    val latestItems by rememberUpdatedState(items)
    val latestPages by rememberUpdatedState(state.pages)
    // SideEffect keeps the resolver's closure fresh every composition without restarting gestures.
    SideEffect {
        pageHitTester.resolve = { y ->
            if (zoomState.scale != 1f) {
                // Zoomed, the canvas offset no longer matches the list's local coordinates.
                null
            } else {
                val visible = listState.layoutInfo.visibleItemsInfo
                val hit = visible.firstOrNull { y >= it.offset && y < it.offset + it.size }
                    ?: visible.minByOrNull { kotlin.math.abs(y - (it.offset + it.size / 2f)) }
                val page = hit?.let { latestItems.getOrNull(it.index)?.page }
                // Two chapters can share the screen; a page belonging to the neighbour would be
                // applied to the wrong chapter's exclusion set, so only the visible chapter counts.
                val chapterRange = state.currentChapterStartIndex until
                    (state.currentChapterStartIndex + state.currentChapterPageCount)
                page?.takeIf { it.index in chapterRange }?.sourceIndex
            }
        }
    }
    DisposableEffect(pageHitTester) {
        onDispose { pageHitTester.resolve = null }
    }
    LaunchedEffect(listState, items) {
        var userScrollStarted = false
        snapshotFlow {
            listOf(
                listState.firstVisibleItemIndex,
                if (listState.isScrollInProgress) 1 else 0,
                if (listState.layoutInfo.visibleItemsInfo.isNotEmpty()) 1 else 0,
                if (listState.canScrollBackward) 1 else 0,
            )
        }.distinctUntilChanged().collect { values ->
            val first = values[0]
            val scrolling = values[1] == 1
            val hasVisibleItems = values[2] == 1
            val canScrollBackward = values[3] == 1
            if (scrolling) userScrollStarted = true
            // Layout may clamp the restored last page below a taller previous page. Only an
            // actual user scroll may replace the progress restored by ReaderViewModel.
            if (!userScrollStarted || !hasVisibleItems) return@collect
            if (first == 0 && !canScrollBackward) onChapterStartReached()
            val page = if (!listState.canScrollForward && first > 0) {
                items.lastOrNull()?.page?.index
            } else {
                items.getOrNull(first)?.page?.index
            }
            page?.let { onAction(ReaderAction.PageShown(it)) }
        }
    }
    LaunchedEffect(state.currentPageIndex) {
        val targetItem = items.indexOfFirst { it.page.index == state.currentPageIndex }.coerceAtLeast(0)
        val targetPageIsVisible = listState.layoutInfo.visibleItemsInfo.any { visible ->
            items.getOrNull(visible.index)?.page?.index == state.currentPageIndex
        }
        if (!targetPageIsVisible) listState.scrollToItem(targetItem)
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
    val tilePaddingPx = with(LocalDensity.current) { 8.dp.toPx() }
    val contentHeightPx = items.sumOf { it.tile.displayHeightPx.toLong() + tilePaddingPx.toLong() }.toFloat()
    SideEffect {
        doubleTapZoomHandler.onDoubleTap = { point ->
            zoomAnimationJob?.cancel()
            animateZoom = true
            zoomState.toggleZoomAt(
                centroid = point,
                pivot = Offset(viewport.widthPx / 2f, viewport.heightPx / 2f),
                viewport = viewport,
                contentWidthPx = viewport.widthPx.toFloat(),
                contentHeightPx = contentHeightPx,
            )
            zoomAnimationJob = zoomAnimationScope.launch {
                delay(260)
                animateZoom = false
            }
        }
        doubleTapZoomHandler.onTransformStart = {
            zoomAnimationJob?.cancel()
            animateZoom = false
        }
        doubleTapZoomHandler.onTransform = { point, factor, pan ->
            zoomState.applyGesture(
                centroid = point,
                pivot = Offset(viewport.widthPx / 2f, viewport.heightPx / 2f),
                pan = pan,
                zoomFactor = factor,
                viewport = viewport,
                contentWidthPx = viewport.widthPx * zoomState.scale,
                contentHeightPx = contentHeightPx * zoomState.scale,
            )
        }
        doubleTapZoomHandler.onPan = { point, pan ->
            if (!zoomState.isZoomed) false else {
                zoomAnimationJob?.cancel()
                animateZoom = false
                zoomState.applyGesture(
                    centroid = point,
                    pivot = Offset(viewport.widthPx / 2f, viewport.heightPx / 2f),
                    pan = pan,
                    zoomFactor = 1f,
                    viewport = viewport,
                    contentWidthPx = viewport.widthPx * zoomState.scale,
                    contentHeightPx = contentHeightPx * zoomState.scale,
                )
                true
            }
        }
        doubleTapZoomHandler.onGestureEnd = {}
    }
    LaunchedEffect(listState, viewport, decoder) {
        if (decoder == null) return@LaunchedEffect
        val jobs = mutableMapOf<Int, Pair<ComicPage, Job>>()
        val completed = mutableMapOf<Int, ComicPage>()
        snapshotFlow {
            latestItems.getOrNull(listState.firstVisibleItemIndex)?.page?.index to latestPages
        }.collect { (index, pages) ->
            if (index == null) return@collect
            val nearby = (-PAGE_PREDECODE_RADIUS..PAGE_PREDECODE_RADIUS)
                .map { index + it }
                .filter { it in pages.indices }
                .toSet()
            jobs.toMap().forEach { (pageIndex, scheduled) ->
                if (pageIndex !in nearby || pages[pageIndex] != scheduled.first) {
                    scheduled.second.cancel()
                    jobs.remove(pageIndex)
                }
            }
            completed.entries.removeAll { (pageIndex, page) -> pageIndex !in nearby || pages[pageIndex] != page }
            nearby.filter { it != index }
                .sortedWith(
                    compareBy<Int> { if (it == index + 1) 0 else 1 }
                        .thenBy { (it - index).let { distance -> if (distance < 0) -distance else distance } },
                )
                .forEach { pageIndex ->
                val page = pages[pageIndex]
                if (page.sizeState != PageSizeState.Ready || completed[pageIndex] == page || jobs[pageIndex]?.first == page) {
                    return@forEach
                }
                val job = launch {
                    try {
                        delay(PAGE_PREDECODE_IDLE_MILLIS)
                        predecodePage(page, viewport, decoder, continuous = true)
                        completed[pageIndex] = page
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // Visible page loading retains its regular retry path.
                    } finally {
                        if (jobs[pageIndex]?.first == page) jobs.remove(pageIndex)
                    }
                }
                jobs[pageIndex] = page to job
            }
        }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = renderedScale
                scaleY = renderedScale
                transformOrigin = TransformOrigin.Center
                translationX = renderedOffsetX
                translationY = renderedOffsetY
            }
            .testTag(if (zoomState.isZoomed) "reader-zoomed" else "reader-not-zoomed"),
        userScrollEnabled = true,
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        items(items, key = { stableTileKey(it) }) { item ->
            PageTile(
                item = item,
                pageNumber = state.pageNumber(item.page.index),
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
    pageHitTester: PageHitTester,
    onChapterEndChange: (Boolean) -> Unit,
    onChapterStartReached: () -> Unit,
    onAction: (ReaderAction) -> Unit,
    doubleTapZoomHandler: ReaderDoubleTapZoomHandler,
) {
    val pagerState = rememberPagerState(
        initialPage = state.currentPageIndex,
        pageCount = { state.pageCount },
    )
    val latestPages by rememberUpdatedState(state.pages)
    // Paged mode shows exactly one page per screen, so the visible page is the touched page.
    SideEffect {
        pageHitTester.resolve = { state.pages.getOrNull(state.currentPageIndex)?.sourceIndex }
    }
    DisposableEffect(pageHitTester) {
        onDispose { pageHitTester.resolve = null }
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }
            .collect { page -> if (page in state.pages.indices) onAction(ReaderAction.PageShown(page)) }
    }
    LaunchedEffect(pagerState) {
        var userSwipeStarted = false
        snapshotFlow { pagerState.currentPage to pagerState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { (page, scrolling) ->
                if (scrolling) userSwipeStarted = true
                else if (userSwipeStarted && page == 0) onChapterStartReached()
            }
    }
    LaunchedEffect(state.currentPageIndex) {
        if (state.currentPageIndex in 0 until pagerState.pageCount && pagerState.currentPage != state.currentPageIndex) {
            pagerState.animateScrollToPage(state.currentPageIndex)
        }
    }
    LaunchedEffect(pagerState.currentPage) { onChapterEndChange(false) }
    LaunchedEffect(pagerState, viewport, decoder) {
        if (decoder == null) return@LaunchedEffect
        val jobs = mutableMapOf<Int, Pair<ComicPage, Job>>()
        val completed = mutableMapOf<Int, ComicPage>()
        snapshotFlow { pagerState.currentPage to latestPages }
            .collect { (currentPage, pages) ->
                val nearby = (-PAGE_PREDECODE_RADIUS..PAGE_PREDECODE_RADIUS)
                    .map { currentPage + it }
                    .filter { it in pages.indices }
                    .toSet()
                jobs.toMap().forEach { (index, scheduled) ->
                    if (index !in nearby || pages[index] != scheduled.first) {
                        scheduled.second.cancel()
                        jobs.remove(index)
                    }
                }
                completed.entries.removeAll { (index, page) -> index !in nearby || pages[index] != page }
                nearby.filter { it != currentPage }
                    .sortedWith(
                        compareBy<Int> { if (it == preferredNextPage(currentPage, state.direction)) 0 else 1 }
                            .thenBy { (it - currentPage).let { distance -> if (distance < 0) -distance else distance } },
                    )
                    .forEach { index ->
                    val page = pages[index]
                    if (page.sizeState != PageSizeState.Ready || completed[index] == page || jobs[index]?.first == page) {
                        return@forEach
                    }
                    val job = launch {
                        try {
                            predecodePage(page, viewport, decoder, continuous = false)
                            completed[index] = page
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            // Visible page loading retains its regular retry path.
                        } finally {
                            if (jobs[index]?.first == page) jobs.remove(index)
                        }
                    }
                    jobs[index] = page to job
                }
            }
    }
    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
        reverseLayout = state.direction == ReadingDirection.RightToLeft,
    ) { index ->
        PagedPage(
            page = state.pages[index],
            pageNumber = state.pageNumber(index),
            viewport = viewport,
            decoder = decoder,
            isLastChapterPage = index == state.pageCount - 1,
            onChapterEndChange = { reached ->
                if (index == pagerState.currentPage) onChapterEndChange(reached)
            },
            onRetry = { onAction(ReaderAction.RetryPage(index)) },
            isCurrentPage = index == pagerState.currentPage,
            doubleTapZoomHandler = doubleTapZoomHandler,
        )
    }
}

/**
 * The end of the series, shown as an overlay instead of an item at the end of the canvas.
 *
 * The old card was appended to the scrolling canvas, so it only appeared after the reader dragged
 * past the last page, and scrolling back even slightly re-reported an earlier page, which cleared
 * the flag and made the card vanish mid-gesture. An overlay follows the reading position instead:
 * it appears while the last page is current and leaves when an earlier page becomes current again.
 */
@Composable
private fun EndOfChaptersNotice(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier
            .padding(horizontal = 20.dp, vertical = 22.dp)
            .fillMaxWidth()
            .testTag("reader-end-of-chapters"),
        shape = RoundedCornerShape(20.dp),
        color = colors.surface.copy(alpha = 0.96f),
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Canvas(Modifier.size(22.dp)) {
                drawCircle(colors.primaryContainer)
                val check = Path().apply {
                    moveTo(size.width * 0.28f, size.height * 0.52f)
                    lineTo(size.width * 0.44f, size.height * 0.68f)
                    lineTo(size.width * 0.74f, size.height * 0.34f)
                }
                drawPath(
                    path = check,
                    color = colors.primary,
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
                )
            }
            Spacer(Modifier.size(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("已到最后一话", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.size(3.dp))
                Text(
                    "这部漫画暂时没有更多章节，更新后可以继续阅读。",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Shown when the chapter itself read fine but its neighbour lookup failed.
 *
 * Saying nothing here would be a lie either way: claiming "已到最后一话" invents an ending, and
 * failing the whole chapter throws away pages that are already on screen. So the pages stay, and
 * this overlay states what is actually known plus a retry that re-asks the source.
 */
@Composable
private fun UnknownNextChapterNotice(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier
            .padding(horizontal = 20.dp, vertical = 22.dp)
            .fillMaxWidth()
            .testTag("reader-next-chapter-unknown"),
        shape = RoundedCornerShape(20.dp),
        color = colors.surface.copy(alpha = 0.96f),
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
    ) {
        Row(
            modifier = Modifier.padding(start = 18.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("无法确认下一话", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.size(3.dp))
                Text(
                    "这一话已可正常阅读，但来源没有返回章节顺序（常见于限流）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
            TextButton(onClick = onRetry) { Text("重试") }
        }
    }
}

/**
 * Wait for active scrolling/flinging to settle before using decode time on adjacent pages. Keeping
 * the work inside collectLatest also cancels stale page decodes when the user moves on quickly.
 */
private suspend fun predecodeNearbyPages(
    currentPage: Int,
    pages: List<ComicPage>,
    viewport: PageViewport,
    decoder: PageImageDecoder,
    continuous: Boolean,
    idleDelayMillis: Long = PAGE_PREDECODE_IDLE_MILLIS,
) {
    if (idleDelayMillis > 0L) delay(idleDelayMillis)
    coroutineScope {
        (-PAGE_PREDECODE_RADIUS..PAGE_PREDECODE_RADIUS)
            .asSequence()
            .map { currentPage + it }
            .filter { it != currentPage }
            .filter { it in pages.indices && pages[it].sizeState == PageSizeState.Ready }
            .forEach { index ->
                launch {
                    val page = pages[index]
                    try {
                        val tile = decoder.plan(page, viewport, zoom = 1f, continuous = continuous).firstOrNull()
                            ?: return@launch
                        decoder.predecode(decodeRequestFor(page, tile))
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // The page's visible tile owns its normal retry and error state.
                    }
                }
            }
    }
}

private suspend fun predecodePage(
    page: ComicPage,
    viewport: PageViewport,
    decoder: PageImageDecoder,
    continuous: Boolean,
) {
    val tile = decoder.plan(page, viewport, zoom = 1f, continuous = continuous).firstOrNull() ?: return
    decoder.predecode(decodeRequestFor(page, tile))
}

/**
 * Builds a decode request for one planned tile, offsetting the region when [page] is a crop of a
 * larger source image (a split double-page half). The plan is computed against the half width, so
 * only the source offset and the source image height need correcting here.
 */
private fun decodeRequestFor(page: ComicPage, tile: PageTile): PageDecodeRequest {
    val crop = page.crop
    val region = if (crop == null) tile.region else PageRegion(
        leftPx = tile.region.leftPx + crop.leftPx,
        topPx = tile.region.topPx + crop.topPx,
        widthPx = tile.region.widthPx,
        heightPx = tile.region.heightPx,
    )
    return PageDecodeRequest(
        path = page.imageRef,
        sourceId = page.sourceId,
        targetWidthPx = tile.displayWidthPx,
        targetHeightPx = tile.displayHeightPx,
        pageIndex = page.index,
        region = region,
        fitWidthOnly = tile.fitWidthOnly,
        headers = page.imageHeaders,
        reverseHorizontalBands = page.reverseHorizontalBands,
        sourceImageHeightPx = crop?.sourceHeightPx ?: page.heightPx,
    )
}

/** A horizontally paged comic page stays fitted to the screen width and scrolls vertically. */
@Composable
private fun PagedPage(
    page: ComicPage,
    pageNumber: Int,
    viewport: PageViewport,
    decoder: PageImageDecoder?,
    isLastChapterPage: Boolean,
    onChapterEndChange: (Boolean) -> Unit,
    onRetry: () -> Unit = {},
    isCurrentPage: Boolean = false,
    doubleTapZoomHandler: ReaderDoubleTapZoomHandler,
) {
    val zoomState = rememberReaderZoomState()
    val zoomAnimationScope = rememberCoroutineScope()
    var animateZoom by remember { mutableStateOf(false) }
    var zoomAnimationJob by remember { mutableStateOf<Job?>(null) }
    val renderedScale by animateFloatAsState(
        targetValue = zoomState.scale,
        animationSpec = if (animateZoom) tween(260, easing = FastOutSlowInEasing) else snap(),
        label = "reader-zoom-scale",
    )
    val renderedOffsetX by animateFloatAsState(
        targetValue = zoomState.offsetX,
        animationSpec = if (animateZoom) tween(260, easing = FastOutSlowInEasing) else snap(),
        label = "reader-zoom-offset-x",
    )
    val renderedOffsetY by animateFloatAsState(
        targetValue = zoomState.offsetY,
        animationSpec = if (animateZoom) tween(260, easing = FastOutSlowInEasing) else snap(),
        label = "reader-zoom-offset-y",
    )
    val tiles = remember(page, viewport, decoder) {
        decoder?.plan(page, viewport, zoom = 1f, continuous = true)
            ?: listOf(placeholderTile(page, viewport, continuous = true))
    }
    val listState = rememberLazyListState()
    val pageReady = page.sizeState == PageSizeState.Ready
    LaunchedEffect(listState, tiles.size, isLastChapterPage, pageReady) {
        if (!isLastChapterPage) return@LaunchedEffect
        snapshotFlow {
            tiles.isNotEmpty() && listState.layoutInfo.totalItemsCount > 0 &&
                !listState.canScrollForward && !zoomState.isZoomed &&
                pageReady
        }.distinctUntilChanged().collect(onChapterEndChange)
    }
    val contentWidthPx = PageTiling.fitWidthScale(page.widthPx, viewport.widthPx) * page.widthPx
    val contentHeightPx = PageTiling.fitWidthScale(page.widthPx, viewport.widthPx) * page.heightPx
    SideEffect {
        if (isCurrentPage) {
            doubleTapZoomHandler.onDoubleTap = { point ->
                zoomAnimationJob?.cancel()
                animateZoom = true
                zoomState.toggleZoomAt(
                    centroid = point,
                    pivot = Offset(viewport.widthPx / 2f, viewport.heightPx / 2f),
                    viewport = viewport,
                    contentWidthPx = contentWidthPx,
                    contentHeightPx = contentHeightPx,
                )
                zoomAnimationJob = zoomAnimationScope.launch {
                    delay(260)
                    animateZoom = false
                }
            }
            doubleTapZoomHandler.onTransformStart = {
                zoomAnimationJob?.cancel()
                animateZoom = false
            }
            val applyPointerTransform: (Offset, Float, Offset) -> Unit = { centroid, zoomFactor, pan ->
                zoomState.applyGesture(
                    centroid = centroid,
                    pivot = Offset(viewport.widthPx / 2f, viewport.heightPx / 2f),
                    pan = pan,
                    zoomFactor = zoomFactor,
                    viewport = viewport,
                    contentWidthPx = contentWidthPx * zoomState.scale,
                    contentHeightPx = contentHeightPx * zoomState.scale,
                )
            }
            doubleTapZoomHandler.onTransform = applyPointerTransform
            doubleTapZoomHandler.onPan = { point, pan ->
                if (!zoomState.isZoomed) {
                    false
                } else {
                    zoomAnimationJob?.cancel()
                    animateZoom = false
                    applyPointerTransform(point, 1f, pan)
                    true
                }
            }
            doubleTapZoomHandler.onGestureEnd = {}
        } else {
            doubleTapZoomHandler.onDoubleTap = null
            doubleTapZoomHandler.onTransformStart = null
            doubleTapZoomHandler.onTransform = null
            doubleTapZoomHandler.onPan = null
            doubleTapZoomHandler.onGestureEnd = null
        }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = renderedScale
                scaleY = renderedScale
                transformOrigin = TransformOrigin.Center
                translationX = renderedOffsetX
                translationY = renderedOffsetY
            }
            .testTag(if (zoomState.isZoomed) "reader-zoomed" else "reader-not-zoomed"),
        userScrollEnabled = !zoomState.isZoomed,
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        item(key = page.imageRef) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                tiles.forEachIndexed { tileIndex, tile ->
                    PageTile(
                        item = TileItem(page, tileIndex, tile),
                        pageNumber = pageNumber,
                        decoder = decoder,
                        onRetry = onRetry,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
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
    pageNumber: Int,
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
                text = "第 $pageNumber 页",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    if (item.page.sizeState != dev.veneranative.core.model.PageSizeState.Ready) {
        Box(modifier = boxModifier, contentAlignment = Alignment.Center) {
            if (item.page.sizeState == dev.veneranative.core.model.PageSizeState.Failed) {
                Button(onClick = onRetry) { Text("重试第 $pageNumber 页") }
            } else CircularProgressIndicator()
        }
        return
    }
    var retryGeneration by remember(item.page.imageRef) { mutableStateOf(0) }
    val imageState by produceState<PageImageState?>(initialValue = null, item.page, tile, decoder, retryGeneration) {
        value = null
        value = try {
            val decoded = decoder.decode(decodeRequestFor(item.page, tile))
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

            PageImageState.Failed -> Button(onClick = { retryGeneration++ }) { Text("重试第 $pageNumber 页") }

            PageImageState.OutOfMemory -> Text(
                text = "图片过大，无法解码。",
                style = MaterialTheme.typography.bodyMedium,
            )

            is PageImageState.Decoded -> FittedPageImage(
                bitmap = current.image.bitmap.asImageBitmap(),
                contentDescription = "第 $pageNumber 页",
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

/** Half order choices offered by the reader toolbar, as (right half first, label). */
private val SPLIT_ORDER_OPTIONS = listOf(
    false to "先左后右",
    true to "先右后左",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderControls(
    direction: ReadingDirection,
    strategy: DecodeStrategy?,
    splitEnabled: Boolean,
    splitOverlap: Float,
    splitRightToLeft: Boolean,
    onDirectionChange: (ReadingDirection) -> Unit,
    onStrategyChange: (DecodeStrategy) -> Unit,
    onSplitChange: () -> Unit,
    onSplitOverlapChange: (Float) -> Unit,
    onSplitOrderChange: (Boolean) -> Unit,
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
        Text("跨页拆分", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FilterChip(
            selected = splitEnabled,
            onClick = onSplitChange,
            label = {
                Text(
                    if (splitEnabled) "自动拆分已开启" else "自动拆分已关闭",
                    maxLines = 1,
                    style = MaterialTheme.typography.labelMedium,
                )
            },
        )
        // The tuning chips only matter while splitting is on; hiding them keeps the toolbar short.
        if (splitEnabled) {
            // The slider is a local draft while dragging and only commits on release: pushing every
            // pixel of movement into the ViewModel would rebuild the page list and restart bitmap
            // decodes dozens of times per drag.
            val overlapDraft = remember { mutableFloatStateOf(splitOverlap) }
            val overlapInteraction = remember { MutableInteractionSource() }
            LaunchedEffect(splitOverlap) {
                if (abs(overlapDraft.floatValue - splitOverlap) > 0.0005f) overlapDraft.floatValue = splitOverlap
            }
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "中缝重叠",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${(overlapDraft.floatValue * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Slider(
                value = overlapDraft.floatValue,
                onValueChange = { overlapDraft.floatValue = it },
                onValueChangeFinished = { onSplitOverlapChange(overlapDraft.floatValue) },
                valueRange = 0f..0.2f,
                // 1% per notch: 20 intervals across 0..20% leaves 19 intermediate steps.
                steps = 19,
                interactionSource = overlapInteraction,
                // The Material3 default thumb in this BOM is a 4x44dp vertical bar; a small dot reads
                // as a slider and leaves the track legible. The horizontal padding keeps the dot from
                // being clipped at either end of the track.
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                thumb = {
                    SliderDefaults.Thumb(
                        interactionSource = overlapInteraction,
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                        ),
                        thumbSize = DpSize(14.dp, 14.dp),
                    )
                },
                track = { sliderState ->
                    val activeColor = MaterialTheme.colorScheme.primary
                    val inactiveColor = MaterialTheme.colorScheme.surfaceVariant
                    Canvas(modifier = Modifier.fillMaxWidth().height(4.dp)) {
                        val centerY = size.height / 2f
                        drawLine(
                            color = inactiveColor,
                            start = Offset(0f, centerY),
                            end = Offset(size.width, centerY),
                            strokeWidth = size.height,
                            cap = StrokeCap.Round,
                        )
                        val activeEnd = size.width * sliderState.coercedValueAsFraction
                        if (activeEnd > 0f) {
                            drawLine(
                                color = activeColor,
                                start = Offset(0f, centerY),
                                end = Offset(activeEnd, centerY),
                                strokeWidth = size.height,
                                cap = StrokeCap.Round,
                            )
                        }
                    }
                },
            )
            Text("拆分顺序", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SPLIT_ORDER_OPTIONS.forEach { (rightToLeft, label) ->
                    FilterChip(
                        selected = rightToLeft == splitRightToLeft,
                        onClick = { onSplitOrderChange(rightToLeft) },
                        label = {
                            Text(label, maxLines = 1, style = MaterialTheme.typography.labelMedium)
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

/**
 * Stable identity for one flattened tile row in the continuous list.
 *
 * It must never include [ComicPage.index]: prepending a previous chapter shifts every display
 * index, so an index-based key invalidates the whole list and makes LazyColumn drop its scroll
 * anchor — the reader then jumps to the top of the freshly inserted chapter and a split page looks
 * like it "lost" its split. The image reference plus the crop origin identifies the source page
 * across chapters, and the tile index separates the tiles of one wide strip.
 */
private fun stableTileKey(item: TileItem): String =
    "${item.page.imageRef}|${item.page.crop?.leftPx ?: -1}|${item.tileIndex}"

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

private const val PAGE_PREDECODE_RADIUS = 3
private const val PAGE_PREDECODE_IDLE_MILLIS = 100L

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
