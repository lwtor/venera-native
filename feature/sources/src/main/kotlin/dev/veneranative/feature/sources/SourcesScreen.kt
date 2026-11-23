package dev.veneranative.feature.sources

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.veneranative.core.designsystem.VeneraNativeTheme
import dev.veneranative.core.model.InstalledSource
import dev.veneranative.core.model.SourceId
import dev.veneranative.data.source.SourceCatalogEntry

private enum class SourcesSection { Available, Installed }

/** Source catalog and locally installed sources have separate, full-height lists. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesScreen(
    state: SourcesUiState,
    onAction: (SourcesAction) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onChooseScript: () -> Unit = {},
) {
    var pendingUninstall by rememberSaveable { mutableStateOf<String?>(null) }
    var section by rememberSaveable { mutableStateOf(SourcesSection.Installed) }
    var manualInstallExpanded by rememberSaveable { mutableStateOf(false) }
    var showAddCatalogDialog by rememberSaveable { mutableStateOf(false) }
    var showSwitchCatalogDialog by rememberSaveable { mutableStateOf(false) }
    var newCatalogLocation by rememberSaveable { mutableStateOf("") }
    var expandedSourceId by rememberSaveable { mutableStateOf<String?>(null) }
    var reorderSourceId by rememberSaveable { mutableStateOf<String?>(null) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("漫画源") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = onChooseScript, enabled = !state.installing) {
                        Icon(Icons.Filled.Add, contentDescription = "从文件安装漫画源")
                    }
                },
            )
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(contentPadding),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                FilterChip(
                    selected = section == SourcesSection.Installed,
                    onClick = { section = SourcesSection.Installed },
                    label = {
                        Text(
                            if (state.catalogUpdateCount > 0) "已安装  ${state.sources.size} · 更新 ${state.catalogUpdateCount}"
                            else "已安装  ${state.sources.size}",
                        )
                    },
                    modifier = Modifier.testTag(INSTALLED_SECTION_TAG),
                )
                FilterChip(
                    selected = section == SourcesSection.Available,
                    onClick = { section = SourcesSection.Available },
                    label = { Text("发现来源  ${state.catalogEntries.size}") },
                    modifier = Modifier.testTag(AVAILABLE_SECTION_TAG),
                )
            }

            state.message?.let { message ->
                MessageRow(message = message, onDismiss = { onAction(SourcesAction.DismissMessage) })
            }

            when (section) {
                SourcesSection.Available -> {
                    CatalogLocationHeader(
                        location = state.catalogLocation,
                        loading = state.catalogStatus == CatalogStatus.Loading,
                        onAdd = { showAddCatalogDialog = true; newCatalogLocation = "" },
                        onSwitch = { showSwitchCatalogDialog = true },
                        onRefresh = { onAction(SourcesAction.RefreshCatalog) },
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("在线目录", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = { manualInstallExpanded = !manualInstallExpanded }) {
                            Text(if (manualInstallExpanded) "收起手动安装" else "手动安装")
                        }
                    }
                    if (manualInstallExpanded) {
                        ManualInstallRow(state = state, onAction = onAction)
                    }
                    Box(Modifier.weight(1f)) {
                        CatalogContent(state = state, onAction = onAction, onInstalledSection = { section = SourcesSection.Installed })
                    }
                }

                SourcesSection.Installed -> Box(Modifier.weight(1f)) {
                    InstalledContent(
                        state = state,
                        onAction = onAction,
                        onUninstall = { pendingUninstall = it.sourceId.value },
                        onDiscover = { section = SourcesSection.Available },
                        expandedSourceId = expandedSourceId,
                        reorderSourceId = reorderSourceId,
                        onToggleExpanded = { source ->
                            if (expandedSourceId == source.sourceId.value) {
                                expandedSourceId = null
                                onAction(SourcesAction.CloseConfiguration)
                            } else {
                                expandedSourceId = source.sourceId.value
                                onAction(SourcesAction.OpenConfiguration(source.sourceId))
                            }
                        },
                        onStartReorder = { reorderSourceId = if (reorderSourceId == it.sourceId.value) null else it.sourceId.value },
                        onMove = { source, delta ->
                            val current = state.sources
                            val index = current.indexOfFirst { it.sourceId == source.sourceId }
                            val target = (index + delta).coerceIn(0, current.lastIndex)
                            if (index != target) onAction(SourcesAction.ReorderSources(current.toMutableList().apply { add(target, removeAt(index)) }.map { it.sourceId }))
                        },
                    )
                }
            }
        }
    }

    val uninstallSource = state.sources.firstOrNull { it.sourceId.value == pendingUninstall }
    if (uninstallSource != null) {
        AlertDialog(
            onDismissRequest = { pendingUninstall = null },
            title = { Text("移除漫画源？") },
            text = { Text("${uninstallSource.name} 及其保存的脚本将从此设备移除。") },
            confirmButton = {
                TextButton(onClick = {
                    pendingUninstall = null
                    onAction(SourcesAction.Uninstall(uninstallSource.sourceId))
                }) { Text("移除") }
            },
            dismissButton = { TextButton(onClick = { pendingUninstall = null }) { Text("取消") } },
        )
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
                            TextButton(
                                onClick = {
                                    showSwitchCatalogDialog = false
                                    if (location != state.catalogLocation.trim()) {
                                        onAction(SourcesAction.SelectCatalogLocation(location))
                                    }
                                },
                                enabled = state.catalogStatus != CatalogStatus.Loading,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    if (location == state.catalogLocation.trim()) "正在使用 · $location" else location,
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

@Composable
private fun CatalogLocationHeader(
    location: String,
    loading: Boolean,
    onAdd: () -> Unit,
    onSwitch: () -> Unit,
    onRefresh: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text("当前漫画源目录", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                location,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(onClick = onAdd, enabled = !loading) { Text("添加") }
            TextButton(onClick = onSwitch, enabled = !loading) { Text("切换") }
            IconButton(onClick = onRefresh, enabled = !loading) {
                Icon(Icons.Filled.Refresh, contentDescription = "刷新来源目录")
            }
        }
    }
}

@Composable
private fun CatalogContent(
    state: SourcesUiState,
    onAction: (SourcesAction) -> Unit,
    onInstalledSection: () -> Unit,
) {
    when (state.catalogStatus) {
        CatalogStatus.Idle -> EmptyListMessage("尚未加载在线目录。请检查目录地址并刷新。")
        CatalogStatus.Loading -> Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.testTag(CATALOG_LOADING_TAG))
            Text("正在加载漫画源目录…")
        }
        CatalogStatus.Failed -> Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("目录加载失败，请检查网址或网络后重试。")
            TextButton(onClick = { onAction(SourcesAction.RefreshCatalog) }) { Text("重试") }
        }
        CatalogStatus.Ready -> if (state.catalogEntries.isEmpty()) {
            EmptyListMessage("目录中没有可用的漫画源。")
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.catalogEntries, key = { it.scriptUrl }) { entry ->
                    CatalogSourceRow(
                        entry = entry,
                        installedSource = state.installedSourceFor(entry),
                        installedFromDifferentCatalog = state.isFromDifferentCatalog(entry),
                        installing = state.installing,
                        onInstall = { onAction(SourcesAction.InstallCatalogEntry(entry)) },
                        onViewInstalled = onInstalledSection,
                    )
                }
            }
        }
    }
}

@Composable
private fun InstalledContent(
    state: SourcesUiState,
    onAction: (SourcesAction) -> Unit,
    onUninstall: (InstalledSource) -> Unit,
    onDiscover: () -> Unit,
    expandedSourceId: String?,
    reorderSourceId: String?,
    onToggleExpanded: (InstalledSource) -> Unit,
    onStartReorder: (InstalledSource) -> Unit,
    onMove: (InstalledSource, Int) -> Unit,
) {
    when (state.status) {
        SourcesStatus.Loading -> Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.testTag(LOADING_TAG))
            Text("正在读取已安装来源…")
        }
        SourcesStatus.Failed -> Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("无法读取已安装的漫画源。")
            TextButton(onClick = { onAction(SourcesAction.Retry) }) { Text("重试") }
        }
        SourcesStatus.Ready -> if (state.isEmpty) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                EmptyListMessage("还没有安装漫画源。打开“发现来源”选择安装，或点击右上角 + 导入文件。")
                Button(onClick = onDiscover) { Text("发现漫画源") }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.sources, key = { it.sourceId.value }) { source ->
                    val update = state.catalogUpdateFor(source)
                    SourceRow(
                        source = source,
                        update = update,
                        installing = state.installing,
                        busy = source.sourceId in state.busySourceIds,
                        onUpdate = { update?.let { onAction(SourcesAction.InstallCatalogEntry(it)) } },
                        onEnabledChange = { enabled -> onAction(SourcesAction.SetEnabled(source.sourceId, enabled)) },
                        onUninstall = { onUninstall(source) },
                        expanded = expandedSourceId == source.sourceId.value,
                        reorderMode = reorderSourceId == source.sourceId.value,
                        configuration = state.configuration?.takeIf { it.source.sourceId == source.sourceId },
                        state = state,
                        onToggleExpanded = { onToggleExpanded(source) },
                        onStartReorder = { onStartReorder(source) },
                        onMove = { delta -> onMove(source, delta) },
                        onAction = onAction,
                    )
                }
            }
        }
    }
}

@Composable
private fun ManualInstallRow(state: SourcesUiState, onAction: (SourcesAction) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = state.installLocation,
                onValueChange = { onAction(SourcesAction.InstallLocationChanged(it)) },
                modifier = Modifier.weight(1f),
                label = { Text("脚本 URL 或文件路径") },
                singleLine = true,
                enabled = !state.installing,
            )
            Button(onClick = { onAction(SourcesAction.Install) }, modifier = Modifier.testTag(MANUAL_INSTALL_TAG), enabled = state.canInstall) {
                Text(if (state.installing) "安装中" else "安装")
            }
        }
        if (state.installing) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
    }
}

@Composable
private fun CatalogSourceRow(
    entry: SourceCatalogEntry,
    installedSource: InstalledSource?,
    installedFromDifferentCatalog: Boolean,
    installing: Boolean,
    onInstall: () -> Unit,
    onViewInstalled: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(entry.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                entry.version?.let { Text("版本 $it", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                entry.description?.takeIf(String::isNotBlank)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (installedSource != null) {
                val sameCatalog = installedSource.catalogLocation != null && installedSource.catalogLocation == entry.catalogLocation
                val differentCatalog = installedFromDifferentCatalog
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        when {
                            sameCatalog -> "已安装 ${installedSource.version}"
                            differentCatalog -> "已在其他目录安装"
                            else -> "已安装 ${installedSource.version}"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onViewInstalled) { Text("查看") }
                    if (differentCatalog) {
                        TextButton(onClick = onInstall, enabled = !installing) {
                            Text(if (installing) "切换中…" else "切换")
                        }
                    } else if (sameCatalog && entry.version != null && entry.version != installedSource.version) {
                        TextButton(onClick = onInstall, enabled = !installing) {
                            Text(if (installing) "更新中…" else "更新至 ${entry.version}")
                        }
                    } else if (sameCatalog) {
                        TextButton(onClick = onInstall, enabled = !installing) {
                            Text(if (installing) "安装中…" else "重新安装")
                        }
                    } else {
                        TextButton(onClick = onInstall, enabled = !installing) {
                            Text(if (installing) "切换中…" else "切换到此目录")
                        }
                    }
                }
            } else {
                TextButton(onClick = onInstall, modifier = Modifier.testTag(CATALOG_INSTALL_TAG), enabled = !installing) {
                    Text(if (installing) "安装中…" else "安装")
                }
            }
        }
    }
}

@Composable
private fun SourceRow(
    source: InstalledSource,
    update: SourceCatalogEntry?,
    installing: Boolean,
    busy: Boolean,
    onUpdate: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onUninstall: () -> Unit,
    expanded: Boolean,
    reorderMode: Boolean,
    configuration: dev.veneranative.core.model.InstalledSourceConfiguration?,
    state: SourcesUiState,
    onToggleExpanded: () -> Unit,
    onStartReorder: () -> Unit,
    onMove: (Int) -> Unit,
    onAction: (SourcesAction) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onToggleExpanded, onLongClick = onStartReorder),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(source.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("版本 ${source.version}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val check = state.loginCheckOutcomes[source.sourceId]
                if (check == dev.veneranative.data.source.SourceLoginCheckOutcome.Invalid || check == dev.veneranative.data.source.SourceLoginCheckOutcome.Failed) {
                    Text("!", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleMedium)
                }
                Text(if (expanded) "收起" else "设置", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 8.dp))
            }
            if (reorderMode) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("调整来源顺序", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                    TextButton(onClick = { onMove(-1) }, enabled = state.sources.firstOrNull()?.sourceId != source.sourceId) { Text("↑ 上移") }
                    TextButton(onClick = { onMove(1) }, enabled = state.sources.lastOrNull()?.sourceId != source.sourceId) { Text("↓ 下移") }
                    TextButton(onClick = onStartReorder) { Text("完成") }
                }
            }
            if (expanded) {
                androidx.compose.material3.HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (source.enabled) "已启用" else "已停用", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (update != null) TextButton(onClick = onUpdate, enabled = !busy && !installing) { Text(if (installing) "更新中…" else "更新 ${update.version}") }
                    TextButton(onClick = onUninstall, enabled = !busy) { Text("移除") }
                    Switch(checked = source.enabled, onCheckedChange = onEnabledChange, enabled = !busy)
                }
                SourceConfigurationPanel(configuration, state, onAction)
            }
        }
    }
}

@Composable
private fun EmptyListMessage(message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Text(message, modifier = Modifier.padding(20.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun MessageRow(message: String, onDismiss: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, modifier = Modifier.weight(1f).padding(vertical = 10.dp), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    }
}

internal const val LOADING_TAG = "sources-loading"
internal const val CATALOG_LOADING_TAG = "source-catalog-loading"
internal const val CATALOG_INSTALL_TAG = "source-catalog-install"
internal const val AVAILABLE_SECTION_TAG = "source-section-available"
internal const val INSTALLED_SECTION_TAG = "source-section-installed"
internal const val MANUAL_INSTALL_TAG = "source-manual-install"

@Preview(showBackground = true)
@Composable
private fun SourcesScreenPreview() {
    VeneraNativeTheme {
        SourcesScreen(
            state = SourcesUiState(
                status = SourcesStatus.Ready,
                sources = listOf(InstalledSource(SourceId("demo"), "Demo Source", "1.0.0", true, "content://demo/source.js")),
                catalogStatus = CatalogStatus.Ready,
                catalogEntries = listOf(SourceCatalogEntry("MangaDex", "manga_dex", "1.2.0", "Public source", "https://example.com/source.js")),
            ),
            onAction = {},
            onBack = {},
        )
    }
}
