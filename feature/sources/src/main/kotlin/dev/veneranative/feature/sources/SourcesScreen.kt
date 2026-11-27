package dev.veneranative.feature.sources

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.veneranative.core.designsystem.VeneraNativeTheme
import dev.veneranative.core.model.InstalledSource
import dev.veneranative.core.model.SourceId
import dev.veneranative.data.source.SourceCatalogEntry
import dev.veneranative.data.source.SourceLoginCheckOutcome
import kotlin.math.abs

private enum class SourcesSection { Available, Installed }

/** Top speed, in pixels per frame, of the list's edge auto-scroll during a long-press drag. */
private const val MAX_EDGE_SCROLL = 14f

/**
 * Installed sources and the catalog behind two tabs.
 *
 * This level answers only "is this source on?" and "where does it sit?". A card carries an enable
 * switch, and everything that needs room — account, script settings, update and removal — moved to
 * [SourceSettingsScreen], reached by tapping the card. Long-pressing a card drags it in place to
 * reorder: no mode to enter and no button to find, just hold and move.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesScreen(
    state: SourcesUiState,
    onAction: (SourcesAction) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onChooseScript: () -> Unit = {},
    onSearchByComic: () -> Unit = {},
    onOpenSettings: (SourceId) -> Unit = {},
) {
    var section by rememberSaveable { mutableStateOf(SourcesSection.Installed) }
    var manualInstallExpanded by rememberSaveable { mutableStateOf(false) }
    var showAddCatalogDialog by rememberSaveable { mutableStateOf(false) }
    var showSwitchCatalogDialog by rememberSaveable { mutableStateOf(false) }
    var newCatalogLocation by rememberSaveable { mutableStateOf("") }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text("漫画源") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = onSearchByComic) {
                        Icon(Icons.Filled.Search, contentDescription = "按漫画查找来源")
                    }
                    IconButton(onClick = onChooseScript, enabled = !state.installing) {
                        Icon(Icons.Filled.Add, contentDescription = "从文件安装漫画源")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { contentPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
            SectionTabs(section = section, state = state, onSelect = { section = it })

            state.message?.let { message ->
                MessageBanner(message = message, onDismiss = { onAction(SourcesAction.DismissMessage) })
            }

            when (section) {
                SourcesSection.Available -> Column(Modifier.weight(1f)) {
                    CatalogLocationCard(
                        location = state.catalogLocation,
                        loading = state.catalogStatus == CatalogStatus.Loading,
                        manualInstallExpanded = manualInstallExpanded,
                        onToggleManualInstall = { manualInstallExpanded = !manualInstallExpanded },
                        onAdd = { newCatalogLocation = ""; showAddCatalogDialog = true },
                        onSwitch = { showSwitchCatalogDialog = true },
                        onRefresh = { onAction(SourcesAction.RefreshCatalog) },
                    )
                    if (manualInstallExpanded) {
                        ManualInstallRow(state = state, onAction = onAction)
                    }
                    Box(Modifier.weight(1f)) {
                        CatalogContent(state = state, onAction = onAction)
                    }
                }

                SourcesSection.Installed -> Box(Modifier.weight(1f)) {
                    InstalledContent(
                        state = state,
                        onAction = onAction,
                        onDiscover = { section = SourcesSection.Available },
                        onOpenSettings = onOpenSettings,
                    )
                }
            }
        }
    }

    if (showAddCatalogDialog) {
        AlertDialog(
            onDismissRequest = { showAddCatalogDialog = false },
            title = { Text("添加漫画源目录") },
            text = {
                OutlinedTextField(
                    value = newCatalogLocation,
                    onValueChange = { newCatalogLocation = it },
                    label = { Text("目录地址") },
                    placeholder = { Text("https://example.com/index.json") },
                    singleLine = true,
                    enabled = state.catalogStatus != CatalogStatus.Loading,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = newCatalogLocation.isNotBlank() && state.catalogStatus != CatalogStatus.Loading,
                    onClick = {
                        val location = newCatalogLocation.trim()
                        onAction(SourcesAction.CatalogLocationChanged(location))
                        onAction(SourcesAction.RefreshCatalog)
                        showAddCatalogDialog = false
                    },
                ) { Text("添加并使用") }
            },
            dismissButton = { TextButton(onClick = { showAddCatalogDialog = false }) { Text("取消") } },
        )
    }

    if (showSwitchCatalogDialog) {
        AlertDialog(
            onDismissRequest = { showSwitchCatalogDialog = false },
            title = { Text("切换漫画源目录") },
            text = {
                if (state.catalogHistory.isEmpty()) {
                    Text("还没有其他已添加的目录。")
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                        items(state.catalogHistory, key = { it }) { location ->
                            val current = location == state.catalogLocation.trim()
                            TextButton(
                                onClick = {
                                    showSwitchCatalogDialog = false
                                    if (!current) onAction(SourcesAction.SelectCatalogLocation(location))
                                },
                                enabled = !current && state.catalogStatus != CatalogStatus.Loading,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    if (current) "正在使用 · $location" else location,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showSwitchCatalogDialog = false }) { Text("关闭") } },
        )
    }
}

/** Two destinations, named after what they hold rather than how they are filtered. */
@Composable
private fun SectionTabs(
    section: SourcesSection,
    state: SourcesUiState,
    onSelect: (SourcesSection) -> Unit,
) {
    TabRow(
        selectedTabIndex = if (section == SourcesSection.Installed) 0 else 1,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.primary,
    ) {
        Tab(
            selected = section == SourcesSection.Installed,
            onClick = { onSelect(SourcesSection.Installed) },
            modifier = Modifier.testTag(INSTALLED_SECTION_TAG),
            text = {
                BadgedBox(
                    badge = {
                        if (state.catalogUpdateCount > 0) {
                            Badge { Text("${state.catalogUpdateCount}") }
                        }
                    },
                ) { Text("已安装 ${state.sources.size}") }
            },
        )
        Tab(
            selected = section == SourcesSection.Available,
            onClick = { onSelect(SourcesSection.Available) },
            modifier = Modifier.testTag(AVAILABLE_SECTION_TAG),
            text = { Text("发现来源 ${state.catalogEntries.size}") },
        )
    }
}

@Composable
private fun InstalledContent(
    state: SourcesUiState,
    onAction: (SourcesAction) -> Unit,
    onDiscover: () -> Unit,
    onOpenSettings: (SourceId) -> Unit,
) {
    when (state.status) {
        SourcesStatus.Loading -> CenteredNotice {
            CircularProgressIndicator(modifier = Modifier.size(28.dp).testTag(LOADING_TAG))
            Text("正在读取已安装来源…", style = MaterialTheme.typography.bodyMedium)
        }

        SourcesStatus.Failed -> CenteredNotice {
            Text("无法读取已安装的漫画源。", style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { onAction(SourcesAction.Retry) }) { Text("重试") }
        }

        SourcesStatus.Ready -> if (state.isEmpty) {
            CenteredNotice {
                Text(
                    "还没有安装漫画源。",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    "打开「发现来源」挑选安装，或用右上角的 + 导入脚本文件。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Button(onClick = onDiscover) { Text("发现漫画源") }
            }
        } else {
            val listState = rememberLazyListState()
            val haptics = LocalHapticFeedback.current
            val density = LocalDensity.current
            var draggingId by remember { mutableStateOf<String?>(null) }
            // Where the finger sits in the list's viewport, independent of which slot the dragged
            // card currently occupies. NaN means no gesture is in progress.
            var fingerY by remember { mutableFloatStateOf(Float.NaN) }
            // Distance from the card's centre to the finger at grab time. Keeping it means the
            // card does not snap its centre onto the finger when the drag starts.
            var grabOffset by remember { mutableFloatStateOf(0f) }
            // Reorder locally while dragging so the layout and the drawn offset change in the same
            // frame. The ViewModel is told once, on release.
            var dragOrder by remember { mutableStateOf<List<SourceId>?>(null) }
            var swapPending by remember { mutableStateOf(false) }
            var autoScroll by remember { mutableFloatStateOf(0f) }
            val displaySources = remember(state.sources, dragOrder) {
                val order = dragOrder
                if (order == null) state.sources else {
                    val rank = order.withIndex().associate { it.value to it.index }
                    state.sources.sortedBy { rank[it.sourceId] ?: Int.MAX_VALUE }
                }
            }
            val latestDisplay = rememberUpdatedState(displaySources)

            fun applyDragCenter() {
                val dragged = draggingId ?: return
                val finger = fingerY
                if (swapPending || !finger.isFinite()) return
                // The card's visual centre tracks the finger minus the grab offset. Compare that
                // against the live layout slots; no offset compensation is needed because the drawn
                // translation is recomputed from the new layout centre on the very next frame.
                val centre = finger - grabOffset
                val visible = listState.layoutInfo.visibleItemsInfo
                val target = visible.firstOrNull { info ->
                    centre >= info.offset && centre < info.offset + info.size
                } ?: return
                val targetId = target.key as? String ?: return
                val next = reorderedSourceIds(
                    current = latestDisplay.value.map { it.sourceId },
                    from = SourceId(dragged),
                    to = SourceId(targetId),
                ) ?: return
                dragOrder = next
                swapPending = true
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }

            // Unlock the next swap only after composition and layout have caught up. Until then
            // the same target would be hit again against the old offsets and undo the swap.
            LaunchedEffect(dragOrder, draggingId) {
                if (dragOrder == null || draggingId == null) return@LaunchedEffect
                withFrameNanos { }
                withFrameNanos { }
                swapPending = false
            }

            // Holding a card against either edge scrolls the list, so sources beyond the viewport
            // can be reached. No manual translation compensation is needed here either: scrolling
            // changes every item's layout centre, and the drawn offset follows that centre.
            LaunchedEffect(draggingId) {
                if (draggingId == null) return@LaunchedEffect
                while (true) {
                    val velocity = autoScroll
                    if (velocity != 0f) {
                        // dispatchRawDelta is the non-suspend scroll used for drag-style panning.
                        listState.dispatchRawDelta(velocity)
                        applyDragCenter()
                    }
                    withFrameNanos { }
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (state.catalogUpdateCount >= 2) {
                    item(key = "update-all-banner") {
                        UpdateAllBanner(
                            count = state.catalogUpdateCount,
                            busy = state.installing,
                            onUpdateAll = { onAction(SourcesAction.UpdateAllOutdated) },
                        )
                    }
                }
                items(displaySources, key = { it.sourceId.value }) { source ->
                    val dragging = draggingId == source.sourceId.value
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .zIndex(if (dragging) 1f else 0f)
                            .graphicsLayer {
                                // Read the layout position at draw time, after any swap in this
                                // frame has been laid out. Deriving the translation from the live
                                // centre keeps the card under the finger without a compensation
                                // step that could run a frame ahead of the layout change.
                                translationY = if (dragging && fingerY.isFinite()) {
                                    val centre = listState.layoutInfo.visibleItemsInfo
                                        .firstOrNull { it.key == source.sourceId.value }
                                        ?.let { it.offset + it.size / 2f }
                                    if (centre != null) draggedTranslation(fingerY, grabOffset, centre) else 0f
                                } else 0f
                            }
                            .pointerInput(source.sourceId.value) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        draggingId = source.sourceId.value
                                        fingerY = Float.NaN
                                        dragOrder = null
                                        swapPending = false
                                        autoScroll = 0f
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    },
                                    onDragEnd = {
                                        draggingId = null
                                        fingerY = Float.NaN
                                        autoScroll = 0f
                                        // Persist once, after the gesture settles. During the drag
                                        // the local order already drove the layout, so there is no
                                        // async gap to compensate against.
                                        dragOrder?.let { onAction(SourcesAction.ReorderSources(it)) }
                                        dragOrder = null
                                        swapPending = false
                                    },
                                    onDragCancel = {
                                        draggingId = null
                                        fingerY = Float.NaN
                                        autoScroll = 0f
                                        dragOrder = null
                                        swapPending = false
                                    },
                                ) { change, _ ->
                                    change.consume()
                                    val self = listState.layoutInfo.visibleItemsInfo
                                        .firstOrNull { it.key == source.sourceId.value }
                                        ?: return@detectDragGesturesAfterLongPress
                                    val centre = self.offset + self.size / 2f
                                    val viewportY = self.offset + change.position.y
                                    if (!fingerY.isFinite()) {
                                        // First movement: remember where on the card the finger
                                        // grabbed so the card does not jump to centre under it.
                                        grabOffset = viewportY - centre
                                    }
                                    // Track the finger in viewport coordinates. After a swap the
                                    // card's own offset changes, so reading change.position alone
                                    // would move the finger with the card and misjudge the edge.
                                    fingerY = viewportY
                                    val viewport = listState.layoutInfo.viewportSize.height
                                    val edge = with(density) { 72.dp.toPx() }
                                    autoScroll = when {
                                        viewportY < edge -> -(edge - viewportY) / edge * MAX_EDGE_SCROLL
                                        viewportY > viewport - edge ->
                                            (viewportY - (viewport - edge)) / edge * MAX_EDGE_SCROLL
                                        else -> 0f
                                    }
                                    applyDragCenter()
                                }
                            },
                    ) {
                        SourceCard(
                            source = source,
                            update = state.catalogUpdateFor(source),
                            state = state,
                            busy = source.sourceId in state.busySourceIds,
                            dragging = dragging,
                            onOpenSettings = { onOpenSettings(source.sourceId) },
                            onEnabledChange = { enabled -> onAction(SourcesAction.SetEnabled(source.sourceId, enabled)) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * One source, one row: the enable switch and the signal that it needs attention. Tapping the card
 * opens its settings page; long-pressing (handled by the parent) drags the card to reorder. Keeping
 * the row this short is what makes the drag target predictable — the whole card is the handle.
 */
@Composable
private fun SourceCard(
    source: InstalledSource,
    update: SourceCatalogEntry?,
    state: SourcesUiState,
    busy: Boolean,
    dragging: Boolean,
    onOpenSettings: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
) {
    val check = state.loginCheckOutcomes[source.sourceId]
    val loginBroken = check == SourceLoginCheckOutcome.Invalid || check == SourceLoginCheckOutcome.Failed
    val meta = buildList {
        add("v${source.version}")
        if (!source.enabled) add("已停用")
        if (loginBroken) add(if (check == SourceLoginCheckOutcome.Failed) "登录检测失败" else "登录已失效")
        if (update?.version != null) add("可更新到 ${update.version}")
    }.joinToString(" · ")
    val metaColor = when {
        loginBroken -> MaterialTheme.colorScheme.error
        update != null -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        modifier = Modifier.fillMaxWidth().testTag(SOURCE_CARD_TAG),
        shape = RoundedCornerShape(16.dp),
        color = if (dragging) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            if (dragging) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
            else MaterialTheme.colorScheme.outlineVariant,
        ),
        tonalElevation = if (dragging) 4.dp else 0.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 11.dp, bottom = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onOpenSettings)
                    .semantics(mergeDescendants = true) {
                        contentDescription = "${source.name}，$meta，点击打开设置"
                    }
                    .padding(vertical = 1.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SourceAvatar(
                    name = source.name,
                    sourceId = source.sourceId.value,
                    dimmed = !source.enabled,
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = source.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (source.enabled) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.bodySmall,
                        color = metaColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(2.dp))
            Switch(
                checked = source.enabled,
                onCheckedChange = onEnabledChange,
                enabled = !busy,
                modifier = Modifier
                    .testTag(SOURCE_ENABLED_TAG)
                    .semantics { contentDescription = "${source.name} 的启用开关" },
            )
        }
    }
}

/**
 * A letter badge standing in for the missing artwork.
 *
 * Sources ship no icon, and a list of bare names reads as a spreadsheet. The tint is derived from
 * the source id so the same source keeps the same colour across sessions without storing anything.
 */
@Composable
private fun SourceAvatar(name: String, sourceId: String, size: Dp = 38.dp, dimmed: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    val palette = listOf(
        scheme.primaryContainer to scheme.onPrimaryContainer,
        scheme.tertiaryContainer to scheme.onTertiaryContainer,
        scheme.secondaryContainer to scheme.onSecondaryContainer,
        scheme.surfaceContainerHighest to scheme.onSurfaceVariant,
    )
    val tint = palette[abs(sourceId.hashCode()) % palette.size]
    Surface(
        modifier = Modifier.size(size).alpha(if (dimmed) 0.45f else 1f),
        shape = RoundedCornerShape(size * 0.3f),
        color = tint.first,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = name.trim().take(1).ifBlank { "?" },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                color = tint.second,
            )
        }
    }
}

@Composable
private fun UpdateAllBanner(count: Int, busy: Boolean, onUpdateAll: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().testTag(UPDATE_ALL_BANNER_TAG),
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "$count 个来源有更新",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            TextButton(onClick = onUpdateAll, enabled = !busy) {
                Text(if (busy) "更新中…" else "全部更新")
            }
        }
    }
}

@Composable
private fun CatalogLocationCard(
    location: String,
    loading: Boolean,
    manualInstallExpanded: Boolean,
    onToggleManualInstall: () -> Unit,
    onAdd: () -> Unit,
    onSwitch: () -> Unit,
    onRefresh: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 4.dp)) {
            Text(
                "当前目录",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = location,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onSwitch, enabled = !loading) { Text("切换") }
                TextButton(onClick = onAdd, enabled = !loading) { Text("添加") }
                TextButton(onClick = onToggleManualInstall, enabled = !loading) {
                    Text(if (manualInstallExpanded) "收起手动安装" else "手动安装")
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onRefresh, enabled = !loading) {
                    Icon(Icons.Filled.Refresh, contentDescription = "刷新来源目录", modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun CatalogContent(state: SourcesUiState, onAction: (SourcesAction) -> Unit) {
    when (state.catalogStatus) {
        CatalogStatus.Idle -> CenteredNotice {
            Text("尚未加载在线目录。", style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { onAction(SourcesAction.RefreshCatalog) }) { Text("加载目录") }
        }

        CatalogStatus.Loading -> CenteredNotice {
            CircularProgressIndicator(modifier = Modifier.size(28.dp).testTag(CATALOG_LOADING_TAG))
            Text("正在加载漫画源目录…", style = MaterialTheme.typography.bodyMedium)
        }

        CatalogStatus.Failed -> CenteredNotice {
            Text("目录加载失败，请检查网址或网络后重试。", style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { onAction(SourcesAction.RefreshCatalog) }) { Text("重试") }
        }

        CatalogStatus.Ready -> if (state.catalogEntries.isEmpty()) {
            CenteredNotice { Text("目录中没有可用的漫画源。", style = MaterialTheme.typography.bodyMedium) }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.catalogEntries, key = { it.scriptUrl }) { entry ->
                    CatalogSourceCard(
                        entry = entry,
                        installedSource = state.installedSourceFor(entry),
                        installedFromDifferentCatalog = state.isFromDifferentCatalog(entry),
                        installing = state.installing,
                        onInstall = { onAction(SourcesAction.InstallCatalogEntry(entry)) },
                    )
                }
            }
        }
    }
}

/**
 * A catalog entry. One status line and at most one action: the previous row stacked up to three
 * buttons on the right, so nothing in it could be read as the main thing to do.
 */
@Composable
private fun CatalogSourceCard(
    entry: SourceCatalogEntry,
    installedSource: InstalledSource?,
    installedFromDifferentCatalog: Boolean,
    installing: Boolean,
    onInstall: () -> Unit,
) {
    val sameCatalog = installedSource != null && installedSource.catalogLocation != null &&
        installedSource.catalogLocation == entry.catalogLocation
    val hasUpdate = sameCatalog && entry.version != null && installedSource?.version != entry.version

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SourceAvatar(name = entry.name, sourceId = entry.key ?: entry.scriptUrl, size = 34.dp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    entry.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val status = when {
                    installedSource == null -> entry.version?.let { "v$it" }
                    installedFromDifferentCatalog -> "已在其他目录安装"
                    hasUpdate -> "已安装 v${installedSource.version} · 可更新到 ${entry.version}"
                    else -> "已安装 v${installedSource.version}"
                }
                Text(
                    text = listOfNotNull(status, entry.description?.takeIf(String::isNotBlank)).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (hasUpdate) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            when {
                installing -> Text(
                    "处理中…",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                installedSource == null -> Button(
                    onClick = onInstall,
                    modifier = Modifier.testTag(CATALOG_INSTALL_TAG),
                ) { Text("安装") }

                hasUpdate || installedFromDifferentCatalog -> FilledTonalButton(onClick = onInstall) {
                    Text(if (hasUpdate) "更新" else "切换")
                }

                else -> Text(
                    "已安装",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ManualInstallRow(state: SourcesUiState, onAction: (SourcesAction) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = state.installLocation,
            onValueChange = { onAction(SourcesAction.InstallLocationChanged(it)) },
            modifier = Modifier.weight(1f),
            label = { Text("脚本 URL 或文件路径") },
            singleLine = true,
            enabled = !state.installing,
            textStyle = MaterialTheme.typography.bodySmall,
        )
        Button(
            onClick = { onAction(SourcesAction.Install) },
            modifier = Modifier.testTag(MANUAL_INSTALL_TAG),
            enabled = state.canInstall,
        ) { Text(if (state.installing) "安装中" else "安装") }
    }
    if (state.installing) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
}

@Composable
internal fun MessageBanner(message: String, onDismiss: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                message,
                modifier = Modifier.weight(1f).padding(vertical = 10.dp),
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    }
}

@Composable
private fun CenteredNotice(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
    ) { content() }
}

internal const val LOADING_TAG = "sources-loading"
internal const val CATALOG_LOADING_TAG = "source-catalog-loading"
internal const val CATALOG_INSTALL_TAG = "source-catalog-install"
internal const val AVAILABLE_SECTION_TAG = "source-section-available"
internal const val INSTALLED_SECTION_TAG = "source-section-installed"
internal const val MANUAL_INSTALL_TAG = "source-manual-install"
internal const val UPDATE_ALL_BANNER_TAG = "source-update-all-banner"
internal const val SOURCE_ENABLED_TAG = "source-enabled-switch"
internal const val SOURCE_CARD_TAG = "source-card"
internal const val SOURCE_SETTINGS_TAG = "source-settings"

/**
 * Translation for the card being dragged, derived rather than accumulated.
 *
 * [fingerY] is the finger's absolute position in the list viewport, [grabOffset] keeps the card
 * from snapping its centre onto the finger at grab time, and [layoutCentre] is the card's *current*
 * position in that viewport. Because the translation is always recomputed from the live centre, a
 * swap or an edge scroll that moves the card to a different slot changes [layoutCentre] and the
 * translation with it in the same frame — the card stays under the finger with no compensation
 * step. Accumulating a delta and subtracting the slot difference instead made the card snap by a
 * whole slot whenever the first item was reordered.
 */
internal fun draggedTranslation(fingerY: Float, grabOffset: Float, layoutCentre: Float): Float =
    (fingerY - grabOffset) - layoutCentre

/**
 * Move the [from] source to wherever [to] currently sits, by identity.
 *
 * The gesture reports layout keys, not data positions: the list may carry an update banner above
 * the sources, so layout index N is not data index N. Reordering by layout index deleted the wrong
 * entry and duplicated the dragged card whenever a banner was present — the first card appeared to
 * jump. Returning null means there is nothing to do: the drop landed on itself, or on a non-source
 * item such as the banner.
 */
internal fun reorderedSourceIds(
    current: List<SourceId>,
    from: SourceId,
    to: SourceId,
): List<SourceId>? {
    if (from == to) return null
    val fromIndex = current.indexOf(from)
    val toIndex = current.indexOf(to)
    if (fromIndex < 0 || toIndex < 0) return null
    return current.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }.toList()
}

@Preview(showBackground = true)
@Composable
private fun SourcesScreenPreview() {
    VeneraNativeTheme {
        SourcesScreen(
            state = SourcesUiState(
                status = SourcesStatus.Ready,
                sources = listOf(
                    InstalledSource(SourceId("demo"), "极简漫画", "1.2.3", true, "content://demo/source.js"),
                    InstalledSource(SourceId("copy"), "拷貝漫畫", "1.8.0", true, "content://copy/source.js"),
                    InstalledSource(SourceId("off"), "Copy Manga", "2.1.0", false, "content://off/source.js"),
                ),
                catalogStatus = CatalogStatus.Ready,
                catalogEntries = listOf(
                    SourceCatalogEntry("MangaDex", "manga_dex", "1.2.0", "Public source", "https://example.com/source.js"),
                ),
            ),
            onAction = {},
            onBack = {},
        )
    }
}
