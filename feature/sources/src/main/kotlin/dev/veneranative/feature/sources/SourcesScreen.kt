package dev.veneranative.feature.sources

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
    var section by rememberSaveable { mutableStateOf(SourcesSection.Available) }
    var manualInstallExpanded by rememberSaveable { mutableStateOf(false) }

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
                    selected = section == SourcesSection.Available,
                    onClick = { section = SourcesSection.Available },
                    label = { Text("可用来源  ${state.catalogEntries.size}") },
                    modifier = Modifier.testTag(AVAILABLE_SECTION_TAG),
                )
                FilterChip(
                    selected = section == SourcesSection.Installed,
                    onClick = { section = SourcesSection.Installed },
                    label = { Text("已安装  ${state.sources.size}") },
                    modifier = Modifier.testTag(INSTALLED_SECTION_TAG),
                )
            }

            state.message?.let { message ->
                MessageRow(message = message, onDismiss = { onAction(SourcesAction.DismissMessage) })
            }

            when (section) {
                SourcesSection.Available -> {
                    CatalogHeader(state = state, onAction = onAction)
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
}

@Composable
private fun CatalogHeader(state: SourcesUiState, onAction: (SourcesAction) -> Unit) {
    OutlinedTextField(
        value = state.catalogLocation,
        onValueChange = { onAction(SourcesAction.CatalogLocationChanged(it)) },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        label = { Text("漫画源目录地址") },
        singleLine = true,
        enabled = state.catalogStatus != CatalogStatus.Loading,
        trailingIcon = {
            IconButton(
                onClick = { onAction(SourcesAction.RefreshCatalog) },
                enabled = state.catalogStatus != CatalogStatus.Loading && state.catalogLocation.isNotBlank(),
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = "刷新来源目录")
            }
        },
    )
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
                        installed = entry.key?.let { key -> state.sources.any { it.sourceId.value == key } } == true,
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
            EmptyListMessage("还没有安装漫画源。你可以从“可用来源”安装，或从文件导入脚本。")
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.sources, key = { it.sourceId.value }) { source ->
                    SourceRow(
                        source = source,
                        busy = source.sourceId in state.busySourceIds,
                        onEnabledChange = { enabled -> onAction(SourcesAction.SetEnabled(source.sourceId, enabled)) },
                        onUninstall = { onUninstall(source) },
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
    installed: Boolean,
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
            if (installed) {
                TextButton(onClick = onViewInstalled) { Text("已安装") }
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
    busy: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onUninstall: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(source.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("版本 ${source.version}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = source.enabled, onCheckedChange = onEnabledChange, enabled = !busy)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(source.origin, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = onUninstall, enabled = !busy) { Text("移除") }
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
