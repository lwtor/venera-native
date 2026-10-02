package dev.veneranative.feature.sources

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.remember
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

/**
 * Stateless sources screen: renders [state] and sends [onAction].
 *
 * All four states are explicit — loading, failed with retry, ready but empty, and ready with a list
 * — because "no sources" and "could not read sources" are different problems for the user.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesScreen(
    state: SourcesUiState,
    onAction: (SourcesAction) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onChooseScript: () -> Unit = {},
) {
    var pendingUninstall by remember { mutableStateOf<InstalledSource?>(null) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("漫画源") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
            )
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
        ) {
            InstallRow(state = state, onAction = onAction, onChooseScript = onChooseScript)
            state.message?.let { message ->
                MessageRow(message = message, onDismiss = { onAction(SourcesAction.DismissMessage) })
            }
            CatalogHeader(state = state, onAction = onAction)
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (state.catalogStatus) {
                    CatalogStatus.Idle -> item { Text("尚未配置漫画源目录。") }
                    CatalogStatus.Loading -> item {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(modifier = Modifier.testTag(CATALOG_LOADING_TAG))
                            Text("正在加载漫画源目录…")
                        }
                    }
                    CatalogStatus.Failed -> item {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("目录加载失败，请检查网址或网络后重试。", modifier = Modifier.weight(1f))
                            TextButton(onClick = { onAction(SourcesAction.RefreshCatalog) }) { Text("重试") }
                        }
                    }
                    CatalogStatus.Ready -> if (state.catalogEntries.isEmpty()) {
                        item { Text("目录中没有可用的漫画源。") }
                    } else {
                        items(state.catalogEntries, key = { it.scriptUrl }) { entry ->
                            CatalogSourceRow(
                                entry = entry,
                                installed = entry.key?.let { key -> state.sources.any { it.sourceId.value == key } } == true,
                                installing = state.installing,
                                onInstall = { onAction(SourcesAction.InstallCatalogEntry(entry)) },
                            )
                        }
                    }
                }
                item { Text("已安装的漫画源", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp)) }
                when (state.status) {
                    SourcesStatus.Loading -> item { CircularProgressIndicator(modifier = Modifier.testTag(LOADING_TAG)) }
                    SourcesStatus.Failed -> item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("无法读取已安装的漫画源。", modifier = Modifier.weight(1f))
                            TextButton(onClick = { onAction(SourcesAction.Retry) }) { Text("重试") }
                        }
                    }
                    SourcesStatus.Ready -> if (state.isEmpty) {
                        item { Text("尚未安装漫画源。请从上方选择，或安装本地脚本。") }
                    } else {
                        items(state.sources, key = { "installed-${it.sourceId.value}" }) { source ->
                            SourceRow(
                                source = source,
                                busy = source.sourceId in state.busySourceIds,
                                onEnabledChange = { enabled -> onAction(SourcesAction.SetEnabled(source.sourceId, enabled)) },
                                onUninstall = { pendingUninstall = source },
                            )
                        }
                    }
                }
            }
        }
    }

    pendingUninstall?.let { source ->
        AlertDialog(
            onDismissRequest = { pendingUninstall = null },
            title = { Text("移除漫画源？") },
            text = { Text("${source.name} 及其保存的脚本将从此设备移除。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingUninstall = null
                        onAction(SourcesAction.Uninstall(source.sourceId))
                    },
                ) {
                    Text("移除")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingUninstall = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun CatalogHeader(state: SourcesUiState, onAction: (SourcesAction) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Venera 漫画源目录", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = state.catalogLocation,
            onValueChange = { onAction(SourcesAction.CatalogLocationChanged(it)) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("目录 JSON 地址") },
            singleLine = true,
            enabled = state.catalogStatus != CatalogStatus.Loading,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when (state.catalogStatus) {
                    CatalogStatus.Idle -> "目录尚未加载"
                    CatalogStatus.Loading -> "正在获取漫画源列表…"
                    CatalogStatus.Ready -> "可用漫画源：${state.catalogEntries.size} 个"
                    CatalogStatus.Failed -> "目录暂不可用"
                },
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(
                onClick = { onAction(SourcesAction.RefreshCatalog) },
                enabled = state.catalogStatus != CatalogStatus.Loading && state.catalogLocation.isNotBlank(),
            ) { Text("刷新") }
        }
    }
}

@Composable
private fun CatalogSourceRow(
    entry: SourceCatalogEntry,
    installed: Boolean,
    installing: Boolean,
    onInstall: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(entry.name, style = MaterialTheme.typography.titleMedium)
                entry.version?.let { Text("版本 $it", style = MaterialTheme.typography.bodySmall) }
                entry.description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            TextButton(
                onClick = onInstall,
                modifier = Modifier.testTag(CATALOG_INSTALL_TAG),
                enabled = !installing && !installed,
            ) {
                Text(if (installed) "已安装" else if (installing) "正在安装…" else "安装")
            }
        }
    }
}

@Composable
private fun InstallRow(
    state: SourcesUiState,
    onAction: (SourcesAction) -> Unit,
    onChooseScript: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = state.installLocation,
            onValueChange = { onAction(SourcesAction.InstallLocationChanged(it)) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("漫画源脚本路径或文件地址") },
            singleLine = true,
            enabled = !state.installing,
        )
        Button(
            onClick = { onAction(SourcesAction.Install) },
            modifier = Modifier.fillMaxWidth(),
            enabled = state.canInstall,
        ) {
            Text(if (state.installing) "正在安装…" else "安装")
        }
        TextButton(onClick = onChooseScript, enabled = !state.installing) {
            Text("选择 JavaScript 文件")
        }
        if (state.installing) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = source.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "版本 ${source.version}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = source.origin,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Switch(
                checked = source.enabled,
                onCheckedChange = onEnabledChange,
                enabled = !busy,
            )
            TextButton(onClick = onUninstall, enabled = !busy) { Text("移除") }
        }
    }
}

@Composable
private fun MessageRow(
    message: String,
    onDismiss: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    }
}

internal const val LOADING_TAG = "sources-loading"
internal const val CATALOG_LOADING_TAG = "source-catalog-loading"
internal const val CATALOG_INSTALL_TAG = "source-catalog-install"

@Preview(showBackground = true)
@Composable
private fun SourcesScreenPreview() {
    VeneraNativeTheme {
        SourcesScreen(
            state = SourcesUiState(
                status = SourcesStatus.Ready,
                sources = listOf(
                    InstalledSource(
                        sourceId = SourceId("demo"),
                        name = "Demo Source",
                        version = "1.0.0",
                        enabled = true,
                        origin = "content://demo/source.js",
                    ),
                ),
            ),
            onAction = {},
            onBack = {},
        )
    }
}
