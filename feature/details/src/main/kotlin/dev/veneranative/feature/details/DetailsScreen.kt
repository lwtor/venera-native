package dev.veneranative.feature.details

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import dev.veneranative.core.image.ComicImageRequest
import dev.veneranative.core.image.compose.ComicImage
import dev.veneranative.core.model.Chapter
import dev.veneranative.core.model.ChapterKey
import dev.veneranative.core.model.Comic
import dev.veneranative.core.model.ComicComment
import dev.veneranative.core.model.SourceId
import androidx.compose.material3.pulltorefresh.PullToRefreshBox


/**
 * Stateless details screen: renders [state] and sends [onAction].
 *
 * The chapter list is whatever the source declared, including its order and its groups: some sources
 * publish one flat list, others several translations or volumes, so the headers and the group chips
 * appear only when the source actually grouped something.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailsScreen(
    state: DetailsUiState,
    onAction: (DetailsAction) -> Unit,
    onOpenChapter: (Chapter) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val tagGroups = state.detail?.let { detail ->
        orderHeroTagGroups(detail.tagGroups.ifEmpty {
            detail.comic.tags.takeIf { it.isNotEmpty() }?.let { mapOf("Tags" to it) }.orEmpty()
        })
    }.orEmpty()
    val metadataRowCount = ((state.detail?.metadata?.size ?: 0) + 1) / 2
    val tagRowCount = tagGroups.values.sumOf { (it.size + 1) / 2 }
    val estimatedHeroHeight = 200 + metadataRowCount * 52 + tagRowCount * 34 + tagGroups.size * 8
    val heroHeight = maxOf(480, estimatedHeroHeight).dp
    val density = androidx.compose.ui.platform.LocalDensity.current
    val collapseDistancePx = with(density) { (heroHeight - 96.dp).toPx() }
    val scrollPx by remember(listState, collapseDistancePx) {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) collapseDistancePx
            else listState.firstVisibleItemScrollOffset.toFloat()
        }
    }
    val collapseFraction = (scrollPx / collapseDistancePx).coerceIn(0f, 1f)
    val title = state.detail?.comic?.title.orEmpty()
    val showContent = state.detail != null && state.status != DetailsStatus.SourceUnavailable
    val isRefreshing = state.status == DetailsStatus.Loading && state.detail != null

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0.dp),
        bottomBar = {
            if (state.detail != null && state.hasChapters) {
                Surface(modifier = Modifier.navigationBarsPadding(), tonalElevation = 3.dp) {
                    Button(
                        onClick = if (state.isChapterSelectionMode) {
                            { onAction(DetailsAction.DownloadSelectedChapters) }
                        } else {
                            {
                                val resumeChapter = state.detail.chapters.firstOrNull { it.key.remoteId == state.lastReadChapterId }
                                (resumeChapter ?: state.detail.chapters.firstOrNull())?.let(onOpenChapter)
                            }
                        },
                        enabled = if (state.isChapterSelectionMode) {
                            state.selectedChapters.isNotEmpty() && !state.isBatchDownloading
                        } else {
                            state.detail.chapters.isNotEmpty()
                        },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        if (state.isChapterSelectionMode) {
                            Text(if (state.isBatchDownloading) "正在加入下载…" else "下载所选 · ${state.selectedChapters.size} 话")
                        } else {
                            val resumeChapter = state.detail.chapters.firstOrNull { it.key.remoteId == state.lastReadChapterId }
                            val primaryChapter = resumeChapter ?: state.detail.chapters.firstOrNull()
                            val action = if (resumeChapter != null) "继续阅读" else "开始阅读"
                            Text(primaryChapter?.let { "$action · ${it.title}" }.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        },
    ) { contentPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
            when {
                showContent -> PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = { onAction(DetailsAction.Refresh) },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Content(
                        state = state,
                        listState = listState,
                        heroHeight = heroHeight,
                        tagGroups = tagGroups,
                        collapseFraction = collapseFraction,
                        onAction = onAction,
                        onOpenChapter = onOpenChapter,
                    )
                }

                state.status == DetailsStatus.Loading -> Centered { CircularProgressIndicator(modifier = Modifier.testTag(DETAILS_LOADING_TAG)) }

                state.status == DetailsStatus.SourceUnavailable -> Centered {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = "此漫画所属的漫画源已卸载或停用。",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }

                else -> Centered {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = state.message ?: "无法获取漫画详情，请重试。",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Button(onClick = { onAction(DetailsAction.Retry) }) { Text("重试") }
                    }
                }

            }

            DetailsToolbar(
                title = title,
                collapseFraction = collapseFraction,
                onBack = onBack,
                isFavorite = state.isFavorite,
                showFavorite = state.hasShelf,
                onToggleFavorite = { onAction(DetailsAction.ToggleFavorite) },
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
    }

    if (state.newFavoriteFolderDraft != null) {
        AlertDialog(
            onDismissRequest = { onAction(DetailsAction.DismissNewFavoriteFolder) },
            title = { Text("新建收藏夹") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = state.newFavoriteFolderDraft,
                        onValueChange = { onAction(DetailsAction.NewFavoriteFolderDraftChanged(it)) },
                        label = { Text("收藏夹名称") },
                        singleLine = true,
                    )
                    state.shelfMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = { TextButton(onClick = { onAction(DetailsAction.CreateFavoriteFolder) }) { Text("创建并勾选") } },
            dismissButton = { TextButton(onClick = { onAction(DetailsAction.DismissNewFavoriteFolder) }) { Text("取消") } },
        )
    } else if (state.favoriteDialog != null) {
        val removing = state.favoriteDialog == FavoriteDialog.Remove
        AlertDialog(
            onDismissRequest = { if (!state.favoriteSaving) onAction(DetailsAction.DismissFavoriteDialog) },
            title = { Text(if (removing) "取消收藏？" else "加入书架") },
            text = {
                if (removing) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("这部漫画会从全部和所有收藏夹中移除，阅读记录不会删除。")
                        state.shelfMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                } else {
                    Column(
                        modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text("全部 · 自动加入", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        state.favoriteFolders.forEach { folder ->
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable { onAction(DetailsAction.FavoriteFolderToggled(folder.id)) },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = folder.id in state.favoriteFolderSelection,
                                    onCheckedChange = { onAction(DetailsAction.FavoriteFolderToggled(folder.id)) },
                                )
                                Text(folder.name)
                            }
                        }
                        TextButton(onClick = { onAction(DetailsAction.NewFavoriteFolderRequested) }) { Text("新建收藏夹") }
                        state.shelfMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !state.favoriteSaving,
                    onClick = { onAction(DetailsAction.ConfirmFavorite) },
                ) { Text(if (removing) "取消收藏" else "加入书架") }
            },
            dismissButton = {
                TextButton(
                    enabled = !state.favoriteSaving,
                    onClick = { onAction(DetailsAction.DismissFavoriteDialog) },
                ) { Text("返回") }
            },
        )
    }
}

@Composable
private fun Content(
    state: DetailsUiState,
    listState: androidx.compose.foundation.lazy.LazyListState,
    heroHeight: androidx.compose.ui.unit.Dp,
    tagGroups: Map<String, List<String>>,
    collapseFraction: Float,
    onAction: (DetailsAction) -> Unit,
    onOpenChapter: (Chapter) -> Unit,
) {
    val chapterBounds = remember { mutableStateMapOf<ChapterKey, Rect>() }
    var listCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val currentBounds by rememberUpdatedState(chapterBounds)
    val currentListCoordinates by rememberUpdatedState(listCoordinates)
    val currentSelected by rememberUpdatedState(state.selectedChapters)
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize()
            .onGloballyPositioned { listCoordinates = it }
            .pointerInput(state.isChapterSelectionMode, state.filteredChapters) {
                if (!state.isChapterSelectionMode) return@pointerInput
                var anchor: ChapterKey? = null
                var lastEnd: ChapterKey? = null
                var desiredSelection = false
                fun chapterAt(position: Offset): ChapterKey? {
                    val rootPosition = currentListCoordinates?.localToRoot(position) ?: return null
                    return currentBounds.entries.firstOrNull { (_, bounds) -> bounds.contains(rootPosition) }?.key
                }
                detectDragGestures(
                    onDragStart = { position ->
                        anchor = chapterAt(position)
                        lastEnd = anchor
                        anchor?.let { desiredSelection = it !in currentSelected }
                    },
                    onDrag = { change, _ ->
                        val start = anchor
                        val end = chapterAt(change.position)
                        if (start != null && end != null && end != lastEnd) {
                            lastEnd = end
                            onAction(DetailsAction.ChapterSelectionRangeChanged(start, end, desiredSelection))
                            change.consume()
                        }
                    },
                    onDragEnd = { anchor = null; lastEnd = null },
                    onDragCancel = { anchor = null; lastEnd = null },
                )
            }
            .testTag(DETAILS_CONTENT_TAG),
        contentPadding = PaddingValues(bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        item {
            Header(
                state = state,
                onAction = onAction,
                heroHeight = heroHeight,
                tagGroups = tagGroups,
                collapseFraction = collapseFraction,
            )
        }
        if (state.status == DetailsStatus.Failed) {
            item {
                Text(
                    state.message ?: "无法获取漫画详情，请下拉重试。",
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        state.downloadMessage?.let { message ->
            item {
                Text(
                    message,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        state.detail?.let { detail ->
            item {
                Section {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(detail.comic.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            detail.comic.subtitle?.takeIf { it.isNotBlank() }?.let {
                                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            state.sourceName?.let {
                                Text("来源 · $it", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        detail.description?.takeIf { it.isNotBlank() }?.let { description ->
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("简介", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text(
                                    text = description,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = if (state.descriptionExpanded) Int.MAX_VALUE else 4,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                TextButton(onClick = { onAction(DetailsAction.DescriptionExpanded(!state.descriptionExpanded)) }) {
                                    Text(if (state.descriptionExpanded) "收起简介" else "展开简介")
                                }
                            }
                        }
                    }
                }
            }
            if (detail.thumbnails.isNotEmpty()) {
                item { Section { DetailThumbnails(title = detail.comic.title, urls = detail.thumbnails, sourceId = detail.comic.key.sourceId) } }
            }
            detail.sourceUrl?.takeIf { it.isHttpUrl() }?.let { url -> item { SourcePageButton(url = url) } }
        }

        if (state.hasNoChapters) {
            item {
                Text(
                    text = "此漫画源没有提供章节。",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            return@LazyColumn
        }

        item {
            Section {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("章节目录", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                            Text("${state.detail?.chapters?.size ?: 0} 话", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { onAction(DetailsAction.ChapterSelectionModeChanged(!state.isChapterSelectionMode)) }) {
                            Text(if (state.isChapterSelectionMode) "取消多选" else "批量选择")
                        }
                    }
                    ChapterControls(state = state, onAction = onAction)
                }
            }
        }

        val withHeaders = state.groupsTheList
        val chapterEntries = buildChapterListEntries(state.filteredChapters, withHeaders)
        chapterEntries.forEach { entry ->
            when (entry) {
                is ChapterListEntry.Group -> item(key = "group:${entry.name}") { GroupHeader(entry.name) }
                is ChapterListEntry.Row -> item(key = "chapter-row:${entry.chapters.first().key.remoteId.value}") {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        entry.chapters.forEach { chapter ->
                            ChapterRow(
                                chapter = chapter,
                                selected = chapter.key in state.selectedChapters,
                                current = chapter.key.remoteId == state.lastReadChapterId,
                                read = chapter.key.remoteId in state.readChapterIds,
                                selectionMode = state.isChapterSelectionMode,
                                modifier = Modifier.weight(1f),
                                onOpen = { onOpenChapter(chapter) },
                                onToggleSelection = { onAction(DetailsAction.ChapterSelectionToggled(chapter.key)) },
                                onBoundsChanged = { bounds ->
                                    if (bounds == null) chapterBounds.remove(chapter.key)
                                    else chapterBounds[chapter.key] = bounds
                                },
                            )
                        }
                        repeat(3 - entry.chapters.size) { Spacer(modifier = Modifier.weight(1f)) }
                    }
                }
            }
        }
        if (state.filteredChapters.isEmpty()) {
            item {
                Text(
                    if (state.chapterQuery.isBlank()) "此分组暂无章节。" else "没有匹配的章节。",
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        state.detail?.comments?.takeIf { it.isNotEmpty() }?.let { comments ->
            item { Section { DetailComments(comments, state.detail.comic.key.sourceId) } }
        }
    }
}

@Composable
private fun Header(
    state: DetailsUiState,
    onAction: (DetailsAction) -> Unit,
    heroHeight: androidx.compose.ui.unit.Dp,
    tagGroups: Map<String, List<String>>,
    collapseFraction: Float,
) {
    val comic = state.detail?.comic ?: return
    Box(
        modifier = Modifier.fillMaxWidth().height(heroHeight).background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        comic.coverUrl?.let { coverUrl ->
            ComicImage(
                request = ComicImageRequest(url = coverUrl, sourceId = comic.key.sourceId, variant = DETAIL_BACKDROP_VARIANT),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                placeholder = {},
            )
        }
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    colors = listOf(Color.Black.copy(alpha = 0.18f), Color.Black.copy(alpha = 0.50f), Color.Black.copy(alpha = 0.92f)),
                ),
            ),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 24.dp)
                .alpha(1f - collapseFraction),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val hasTagGroups = tagGroups.any { (_, values) -> values.isNotEmpty() }
            if (state.detail.metadata.isNotEmpty()) {
                HeroFacts(
                    metadata = state.detail.metadata,
                    trailingFavorite = if (!hasTagGroups && state.hasShelf) {
                        { FavoriteAction(state.isFavorite) { onAction(DetailsAction.ToggleFavorite) } }
                    } else null,
                )
            }
            if (hasTagGroups) {
                HeroTagGroups(
                    groups = tagGroups,
                    trailingFavorite = if (state.hasShelf) {
                        { FavoriteAction(state.isFavorite) { onAction(DetailsAction.ToggleFavorite) } }
                    } else null,
                )
            }
            if (state.detail.metadata.isEmpty() && !hasTagGroups && state.hasShelf) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    FavoriteAction(state.isFavorite) { onAction(DetailsAction.ToggleFavorite) }
                }
            }
        }
        state.shelfMessage?.let { message ->
            Text(
                text = message,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 20.dp, bottom = 30.dp).alpha(1f - collapseFraction),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White,
            )
        }
    }
}

@Composable
private fun DetailsToolbar(
    title: String,
    collapseFraction: Float,
    onBack: () -> Unit,
    isFavorite: Boolean,
    showFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val background = androidx.compose.ui.graphics.lerp(Color.Transparent, MaterialTheme.colorScheme.surface, collapseFraction)
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = background,
        shadowElevation = 2.dp * collapseFraction,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                .height(56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .padding(start = 4.dp)
                    .semantics { contentDescription = "返回" },
            ) {
                CanvasBackArrow(
                    color = androidx.compose.ui.graphics.lerp(Color.White, MaterialTheme.colorScheme.onSurface, collapseFraction),
                )
            }
            Text(
                text = title,
                modifier = Modifier.weight(1f).alpha(collapseFraction),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (showFavorite) {
                IconButton(
                    onClick = onToggleFavorite,
                    modifier = Modifier.alpha(collapseFraction).semantics {
                        contentDescription = if (isFavorite) "取消收藏" else "收藏"
                    },
                    enabled = collapseFraction >= 0.98f,
                ) {
                    BookmarkGlyph(
                        favorite = isFavorite,
                        color = androidx.compose.ui.graphics.lerp(Color.White, MaterialTheme.colorScheme.primary, collapseFraction),
                    )
                }
            } else {
                Spacer(modifier = Modifier.width(16.dp))
            }
        }
    }
}

@Composable
private fun BookmarkGlyph(favorite: Boolean, color: Color) {
    androidx.compose.foundation.Canvas(modifier = Modifier.size(24.dp)) {
        val path = Path().apply {
            moveTo(size.width * .34f, size.height * .12f)
            cubicTo(size.width * .25f, size.height * .12f, size.width * .2f, size.height * .17f, size.width * .2f, size.height * .27f)
            lineTo(size.width * .2f, size.height * .88f)
            lineTo(size.width * .5f, size.height * .72f)
            lineTo(size.width * .8f, size.height * .88f)
            lineTo(size.width * .8f, size.height * .27f)
            cubicTo(size.width * .8f, size.height * .17f, size.width * .75f, size.height * .12f, size.width * .66f, size.height * .12f)
            close()
        }
        if (favorite) drawPath(path, color)
        else drawPath(
            path,
            color,
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = 1.8.dp.toPx(),
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
                join = androidx.compose.ui.graphics.StrokeJoin.Round,
            ),
        )
    }
}

@Composable
private fun CanvasBackArrow(color: Color) {
    androidx.compose.foundation.Canvas(modifier = Modifier.size(24.dp)) {
        val path = Path().apply {
            moveTo(size.width * .72f, size.height * .18f)
            lineTo(size.width * .38f, size.height * .5f)
            lineTo(size.width * .72f, size.height * .82f)
            moveTo(size.width * .4f, size.height * .5f)
            lineTo(size.width * .9f, size.height * .5f)
        }
        drawPath(path, color = color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
    }
}

@Composable
private fun Section(content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp)) {
        content()
    }
}

@Composable
private fun HeroFacts(metadata: Map<String, String>, trailingFavorite: (@Composable () -> Unit)? = null) {
    if (metadata.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val factRows = metadata.entries.toList().chunked(2)
        factRows.forEachIndexed { rowIndex, rowFacts ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                rowFacts.forEach { (label, value) ->
                    Surface(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        color = Color.White.copy(alpha = 0.16f),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                label.toChineseMetadataLabel(),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.78f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                value,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                if (rowFacts.size == 1) Spacer(modifier = Modifier.weight(1f))
                if (rowIndex == factRows.lastIndex) trailingFavorite?.invoke()
            }
        }
    }
}

@Composable
private fun FavoriteAction(isFavorite: Boolean, onToggleFavorite: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = Color.Black.copy(alpha = 0.34f),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.68f)),
    ) {
        IconButton(
            onClick = onToggleFavorite,
            modifier = Modifier.size(44.dp).semantics {
                contentDescription = if (isFavorite) "取消收藏" else "收藏"
            },
        ) { BookmarkGlyph(favorite = isFavorite, color = Color.White) }
    }
}

internal fun orderHeroTagGroups(groups: Map<String, List<String>>): Map<String, List<String>> {
    val orderedKeys = groups.keys.toMutableList()
    val updateIndex = orderedKeys.indexOfFirst { it.toChineseTagGroupLabel() == "更新" }
    val tagsIndex = orderedKeys.indexOfFirst { it.toChineseTagGroupLabel() == "标签" }
    if (updateIndex >= 0 && tagsIndex >= 0 && updateIndex < tagsIndex) {
        val updateKey = orderedKeys[updateIndex]
        orderedKeys[updateIndex] = orderedKeys[tagsIndex]
        orderedKeys[tagsIndex] = updateKey
    }
    return orderedKeys.associateWith { groups.getValue(it) }
}

@Composable
private fun InfoLabel(text: String) {
    Surface(shape = RoundedCornerShape(7.dp), color = MaterialTheme.colorScheme.primaryContainer) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HeroTagGroups(
    groups: Map<String, List<String>>,
    trailingFavorite: (@Composable () -> Unit)? = null,
) {
    val visibleGroups = groups.filterValues { it.isNotEmpty() }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        visibleGroups.entries.forEachIndexed { index, (name, values) ->
            Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(shape = RoundedCornerShape(7.dp), color = Color.White.copy(alpha = 0.16f)) {
                        Text(
                            name.toChineseTagGroupLabel(),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.84f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    FlowRow(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        values.forEach { value ->
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = Color.White.copy(alpha = 0.2f),
                            ) {
                                androidx.compose.foundation.text.BasicText(
                                    text = value,
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                                    style = MaterialTheme.typography.labelSmall.copy(color = Color.White),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    if (index == visibleGroups.size - 1) trailingFavorite?.invoke()
                }
        }
    }
}

@Composable
private fun DetailThumbnails(title: String, urls: List<String>, sourceId: SourceId) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("内容预览", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(urls) { index, url ->
                Card(modifier = Modifier.size(width = 96.dp, height = 144.dp)) {
                    ComicImage(
                        request = ComicImageRequest(url = url, sourceId = sourceId, variant = "$DETAIL_THUMBNAIL_VARIANT-$index"),
                        contentDescription = "$title 预览图 ${index + 1}",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        placeholder = { CoverTitle(title = title) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailComments(comments: List<ComicComment>, sourceId: SourceId) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("评论（${comments.size}）", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        comments.forEach { comment ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        comment.avatarUrl?.let { avatar ->
                            Surface(modifier = Modifier.size(32.dp), shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceVariant) {
                                ComicImage(
                                    request = ComicImageRequest(url = avatar, sourceId = sourceId, variant = DETAIL_COMMENT_AVATAR_VARIANT),
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop,
                                )
                            }
                        }
                        Text(comment.userName, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                        comment.time?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    Text(comment.content, style = MaterialTheme.typography.bodyMedium)
                    val extras = listOfNotNull(
                        comment.score?.let { "评分 $it" },
                        comment.replyCount?.takeIf { it > 0 }?.let { "$it 条回复" },
                    )
                    if (extras.isNotEmpty()) {
                        Text(extras.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun SourcePageButton(url: String) {
    val uriHandler = LocalUriHandler.current
    TextButton(
        onClick = { runCatching { uriHandler.openUri(url) } },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
    ) { Text("在漫画源网页打开") }
}

@Composable
private fun CoverTitle(title: String) {
    Box(
        modifier = Modifier.padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ChapterControls(
    state: DetailsUiState,
    onAction: (DetailsAction) -> Unit,
) {
    var searchExpanded by remember { mutableStateOf(state.chapterQuery.isNotBlank()) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ChapterOrder.entries.forEach { order ->
                FilterChip(
                    selected = state.order == order,
                    onClick = { onAction(DetailsAction.OrderSelected(order)) },
                    label = { Text(order.label()) },
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            IconButton(
                onClick = {
                    if (searchExpanded) onAction(DetailsAction.ChapterQueryChanged(""))
                    searchExpanded = !searchExpanded
                },
                modifier = Modifier.semantics { contentDescription = "搜索章节" },
            ) {
                SearchGlyph()
            }
        }

        if (searchExpanded) {
            OutlinedTextField(
                value = state.chapterQuery,
                onValueChange = { onAction(DetailsAction.ChapterQueryChanged(it)) },
                modifier = Modifier.fillMaxWidth().testTag(DETAILS_CHAPTER_SEARCH_TAG),
                singleLine = true,
                placeholder = { Text("搜索章节") },
            )
        }

        if (state.groups.size > 1) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("版本筛选", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = state.selectedGroup == null,
                        onClick = { onAction(DetailsAction.GroupSelected(null)) },
                        label = { Text("全部 · ${state.detail?.chapters?.size ?: 0}") },
                    )
                    state.groups.forEach { group ->
                        val groupCount = state.detail?.chapters?.count { it.group == group } ?: 0
                        FilterChip(
                            selected = state.selectedGroup == group,
                            onClick = { onAction(DetailsAction.GroupSelected(group)) },
                            label = { Text("$group · $groupCount", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        )
                    }
                }
            }
        }

        if (state.isChapterSelectionMode) {
            val allVisibleSelected = state.filteredChapters.isNotEmpty() && state.filteredChapters.all { it.key in state.selectedChapters }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onAction(DetailsAction.VisibleChaptersSelected(!allVisibleSelected)) }) {
                    Text(if (allVisibleSelected) "取消全选" else "全选当前结果")
                }
                Text("已选 ${state.selectedChapters.size} 话", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SearchGlyph() {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    androidx.compose.foundation.Canvas(modifier = Modifier.size(20.dp)) {
        drawCircle(
            color = color,
            radius = size.minDimension * 0.30f,
            center = androidx.compose.ui.geometry.Offset(size.width * 0.42f, size.height * 0.42f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.8.dp.toPx()),
        )
        drawLine(
            color = color,
            start = androidx.compose.ui.geometry.Offset(size.width * 0.63f, size.height * 0.63f),
            end = androidx.compose.ui.geometry.Offset(size.width * 0.9f, size.height * 0.9f),
            strokeWidth = 1.8.dp.toPx(),
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
    }
}

@Composable
private fun GroupHeader(name: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Text(
                text = "版本",
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Text(
            text = name,
            modifier = Modifier.weight(1f, fill = false),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        androidx.compose.material3.HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f),
        )
    }
}

private sealed interface ChapterListEntry {
    data class Group(val name: String) : ChapterListEntry
    data class Row(val chapters: List<Chapter>) : ChapterListEntry
}

private fun buildChapterListEntries(chapters: List<Chapter>, showGroups: Boolean): List<ChapterListEntry> {
    val entries = mutableListOf<ChapterListEntry>()
    val row = mutableListOf<Chapter>()
    var previousGroup: String? = null

    fun flushRow() {
        if (row.isNotEmpty()) {
            entries += ChapterListEntry.Row(row.toList())
            row.clear()
        }
    }

    chapters.forEach { chapter ->
        val group = chapter.group
        if (showGroups && group != null && group != previousGroup) {
            flushRow()
            entries += ChapterListEntry.Group(group)
        }
        if (showGroups) previousGroup = group ?: previousGroup

        row += chapter
        if (row.size == 3) flushRow()
    }
    flushRow()
    return entries
}

@Composable
private fun ChapterRow(
    chapter: Chapter,
    selected: Boolean,
    current: Boolean,
    read: Boolean,
    selectionMode: Boolean,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
    onToggleSelection: () -> Unit,
    onBoundsChanged: (Rect?) -> Unit,
) {
    DisposableEffect(chapter.key) {
        onDispose { onBoundsChanged(null) }
    }
    Card(
        modifier = modifier.fillMaxWidth().padding(vertical = 2.dp)
            .onGloballyPositioned { onBoundsChanged(it.boundsInRoot()) }
            .clickable(onClick = if (selectionMode) onToggleSelection else onOpen),
        shape = RoundedCornerShape(9.dp),
        colors = CardDefaults.cardColors(
            containerColor = when {
                selected -> MaterialTheme.colorScheme.secondaryContainer
                current -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
        border = if (current && !selected) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (selectionMode) {
                Surface(
                    modifier = Modifier.size(14.dp),
                    shape = RoundedCornerShape(4.dp),
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (selected) {
                            Text(
                                text = "✓",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        }
                    }
                }
            }
            Text(
                text = chapter.title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = when {
                    selected -> MaterialTheme.colorScheme.onSecondaryContainer
                    current -> MaterialTheme.colorScheme.onPrimaryContainer
                    read -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.58f)
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

private fun ChapterOrder.label(): String = when (this) {
    ChapterOrder.SourceOrder -> "来源顺序"
    ChapterOrder.Reversed -> "倒序"
}

private fun String.toChineseMetadataLabel(): String = when (this) {
    "Uploader" -> "上传者"
    "Uploaded" -> "上传时间"
    "Updated" -> "更新时间"
    "Rating" -> "评分"
    "Likes" -> "点赞数"
    "Comments" -> "评论数"
    else -> this
}

private fun String.toChineseTagGroupLabel(): String = when (this) {
    "Tags" -> "标签"
    "Updated", "Update" -> "更新"
    "Status" -> "状态"
    "Authors" -> "作者"
    "Artists" -> "画师"
    "Genres" -> "题材"
    "Characters" -> "角色"
    "Parodies" -> "原作"
    "Groups" -> "社团"
    "Categories" -> "分类"
    else -> this
}

private fun String.isHttpUrl(): Boolean = startsWith("https://", ignoreCase = true) || startsWith("http://", ignoreCase = true)

internal const val DETAILS_LOADING_TAG = "details-loading"
internal const val DETAILS_CONTENT_TAG = "details-content"
internal const val DETAILS_CHAPTER_SEARCH_TAG = "details-chapter-search"

/** Keeps a cover's cache entry apart from a page that happens to reuse the same URL. */
private const val DETAIL_THUMBNAIL_VARIANT = "detail-thumbnail"
private const val DETAIL_BACKDROP_VARIANT = "detail-backdrop"
private const val DETAIL_COMMENT_AVATAR_VARIANT = "detail-comment-avatar"
