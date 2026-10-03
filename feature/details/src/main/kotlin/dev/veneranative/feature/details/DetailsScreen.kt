package dev.veneranative.feature.details

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
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
import dev.veneranative.core.model.ComicKey
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
    onOpenChapter: (ChapterKey) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenComic: (ComicKey) -> Unit = {},
) {
    val listState = rememberLazyListState()
    val heroHeight = 440.dp
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
                val firstChapter = state.detail.chapters.firstOrNull()
                if (firstChapter != null) {
                    Surface(modifier = Modifier.navigationBarsPadding(), tonalElevation = 3.dp) {
                        Button(
                            onClick = { onOpenChapter(firstChapter.key) },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        ) { Text("开始阅读 · ${firstChapter.title}", maxLines = 1, overflow = TextOverflow.Ellipsis) }
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
                        collapseFraction = collapseFraction,
                        onAction = onAction,
                        onOpenChapter = onOpenChapter,
                        onOpenComic = onOpenComic,
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
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
    }
}

@Composable
private fun Content(
    state: DetailsUiState,
    listState: androidx.compose.foundation.lazy.LazyListState,
    heroHeight: androidx.compose.ui.unit.Dp,
    collapseFraction: Float,
    onAction: (DetailsAction) -> Unit,
    onOpenChapter: (ChapterKey) -> Unit,
    onOpenComic: (ComicKey) -> Unit,
) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().testTag(DETAILS_CONTENT_TAG),
        contentPadding = PaddingValues(bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        item { Header(state = state, onAction = onAction, heroHeight = heroHeight, collapseFraction = collapseFraction) }
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

        state.detail?.metadata?.takeIf { it.isNotEmpty() }?.let { metadata ->
            item { Section { DetailFacts(metadata) } }
        }

        state.detail?.let { detail ->
            val groups = detail.tagGroups.ifEmpty {
                detail.comic.tags.takeIf { it.isNotEmpty() }?.let { mapOf("Tags" to it) }.orEmpty()
            }
            if (groups.isNotEmpty()) item { Section { DetailTagGroups(groups) } }
            if (detail.thumbnails.isNotEmpty()) {
                item { Section { DetailThumbnails(title = detail.comic.title, urls = detail.thumbnails, sourceId = detail.comic.key.sourceId) } }
            }
            detail.sourceUrl?.takeIf { it.isHttpUrl() }?.let { url -> item { SourcePageButton(url = url) } }
            if (detail.recommendations.isNotEmpty()) {
                item { Section { DetailRecommendations(detail.recommendations, onOpenComic) } }
            }
        }

        state.detail?.description?.takeIf { it.isNotBlank() }?.let { description ->
            item {
                Section {
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
        var previousGroup: String? = null
        state.filteredChapters.forEach { chapter ->
            val group = chapter.group
            if (withHeaders && group != null && group != previousGroup) {
                item(key = "group:$group") { GroupHeader(group) }
            }
            if (withHeaders) previousGroup = group ?: previousGroup

            item(key = chapter.key.remoteId.value) {
                ChapterRow(
                    chapter = chapter,
                    selected = chapter.key in state.selectedChapters,
                    selectionMode = state.isChapterSelectionMode,
                    onOpen = { onOpenChapter(chapter.key) },
                    onDownload = { onAction(DetailsAction.DownloadChapter(chapter.key)) },
                    onToggleSelection = { onAction(DetailsAction.ChapterSelectionToggled(chapter.key)) },
                )
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
            Text(text = comic.title, style = MaterialTheme.typography.headlineSmall, color = Color.White, fontWeight = FontWeight.Bold)
            comic.subtitle?.takeIf { it.isNotBlank() }?.let { subtitle ->
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.88f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            state.sourceName?.let { Text("来源 · $it", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.82f)) }
            if (state.hasShelf) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = { onAction(DetailsAction.ToggleFavorite) },
                        shape = RoundedCornerShape(50),
                    ) { Text(if (state.isFavorite) "✓ 已收藏" else "＋ 收藏", color = Color.White) }
                }
            }
            state.shelfMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Color.White) }
        }
    }
}

@Composable
private fun DetailsToolbar(
    title: String,
    collapseFraction: Float,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val background = androidx.compose.ui.graphics.lerp(Color.Transparent, MaterialTheme.colorScheme.surface, collapseFraction)
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = background,
        shadowElevation = 2.dp * collapseFraction,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().statusBarsPadding().height(56.dp),
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
                modifier = Modifier.weight(1f).padding(end = 16.dp).alpha(collapseFraction),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
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
private fun DetailFacts(metadata: Map<String, String>) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            metadata.forEach { (label, value) ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        InfoLabel(label.toChineseMetadataLabel())
                        Text(
                            value,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
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
private fun DetailTagGroups(groups: Map<String, List<String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        groups.forEach { (name, values) ->
            if (values.isNotEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        InfoLabel(name.toChineseTagGroupLabel())
                        FlowRow(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            values.forEach { value ->
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                ) {
                                    Text(
                                        value,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    )
                                }
                            }
                        }
                    }
                }
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
private fun DetailRecommendations(comics: List<Comic>, onOpenComic: (ComicKey) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("相关推荐", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            itemsIndexed(comics, key = { index, comic -> "${comic.key.sourceId.value.length}:${comic.key.sourceId.value}:${comic.key.remoteId.value}:$index" }) { _, comic ->
                Card(
                    modifier = Modifier.width(126.dp).clickable { onOpenComic(comic.key) },
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Column {
                        Box(modifier = Modifier.fillMaxWidth().height(154.dp).background(MaterialTheme.colorScheme.surfaceVariant)) {
                            comic.coverUrl?.let { coverUrl ->
                                ComicImage(
                                    request = ComicImageRequest(url = coverUrl, sourceId = comic.key.sourceId, variant = DETAIL_RECOMMENDATION_VARIANT),
                                    contentDescription = comic.title,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop,
                                    placeholder = { CoverTitle(comic.title) },
                                )
                            } ?: CoverTitle(comic.title)
                        }
                        Text(
                            comic.title,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
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
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = state.chapterQuery,
            onValueChange = { onAction(DetailsAction.ChapterQueryChanged(it)) },
            modifier = Modifier.fillMaxWidth().testTag(DETAILS_CHAPTER_SEARCH_TAG),
            singleLine = true,
            label = { Text("搜索章节") },
            placeholder = { Text("输入章节名称") },
        )
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
        }

        if (state.groups.size > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = state.selectedGroup == null,
                    onClick = { onAction(DetailsAction.GroupSelected(null)) },
                    label = { Text("全部") },
                )
                state.groups.forEach { group ->
                    FilterChip(
                        selected = state.selectedGroup == group,
                        onClick = { onAction(DetailsAction.GroupSelected(group)) },
                        label = { Text(group, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
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
                Spacer(modifier = Modifier.weight(1f))
                Button(
                    onClick = { onAction(DetailsAction.DownloadSelectedChapters) },
                    enabled = state.selectedChapters.isNotEmpty() && !state.isBatchDownloading,
                ) {
                    Text(if (state.isBatchDownloading) "正在加入…" else "下载所选")
                }
            }
        }
    }
}

@Composable
private fun GroupHeader(name: String) {
    Text(
        text = name,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun ChapterRow(
    chapter: Chapter,
    selected: Boolean,
    selectionMode: Boolean,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
    onToggleSelection: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp)
            .clickable(onClick = if (selectionMode) onToggleSelection else onOpen),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = if (selectionMode) if (selected) "✓" else "○" else (chapter.index + 1).toString(),
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = chapter.title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!selectionMode) {
                TextButton(onClick = onDownload) { Text("下载") }
            }
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
private const val DETAIL_RECOMMENDATION_VARIANT = "detail-recommendation"
private const val DETAIL_COMMENT_AVATAR_VARIANT = "detail-comment-avatar"
