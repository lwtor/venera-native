package dev.veneranative.feature.sources

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.veneranative.core.model.SourceId

/**
 * One source's parameters, on a page of its own.
 *
 * The list level answers "is it on" and "where does it sit"; everything that needs room — account,
 * script settings, update and removal — lives here. Keeping it a destination rather than an
 * expanding card means a script can declare as many settings as it likes without squashing the
 * cards around it, and back always means "return to the list".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SourceSettingsScreen(
    sourceId: SourceId?,
    state: SourcesUiState,
    onAction: (SourcesAction) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pendingUninstall by remember { mutableStateOf(false) }
    val source = state.sources.firstOrNull { it.sourceId == sourceId }
    val update = source?.let(state::catalogUpdateFor)
    val configuration = state.configuration?.takeIf { it.source.sourceId == sourceId }
    val busy = source != null && source.sourceId in state.busySourceIds

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text(source?.name ?: "来源设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { contentPadding ->
        when {
            source == null && state.status == SourcesStatus.Loading -> Box(
                modifier = Modifier.fillMaxSize().padding(contentPadding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp).testTag(LOADING_TAG))
            }

            source == null -> Column(
                modifier = Modifier.fillMaxSize().padding(contentPadding).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            ) {
                Text(
                    "这个漫画源已不存在。",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
                TextButton(onClick = onBack) { Text("返回漫画源列表") }
            }

            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(top = 10.dp, bottom = 28.dp)
                    .testTag(SOURCE_SETTINGS_TAG),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                state.message?.let { message ->
                    MessageBanner(message = message, onDismiss = { onAction(SourcesAction.DismissMessage) })
                }
                Text(
                    text = "v${source.version}" + if (source.enabled) "" else " · 已停用",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SourceSettingsBody(
                    configuration = configuration,
                    configurationLoading = state.configurationLoading,
                    state = state,
                    busy = busy,
                    onAction = onAction,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
                SourceMaintenanceFooter(
                    update = update,
                    installing = state.installing,
                    busy = busy,
                    onUpdate = { update?.let { onAction(SourcesAction.InstallCatalogEntry(it)) } },
                    onUninstall = { pendingUninstall = true },
                )
            }
        }
    }

    if (pendingUninstall && source != null) {
        AlertDialog(
            onDismissRequest = { pendingUninstall = false },
            title = { Text("移除漫画源？") },
            text = { Text("${source.name} 及其保存的脚本将从此设备移除。") },
            confirmButton = {
                TextButton(onClick = {
                    pendingUninstall = false
                    onAction(SourcesAction.Uninstall(source.sourceId))
                    onBack()
                }) { Text("移除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingUninstall = false }) { Text("取消") } },
        )
    }
}

/** Update and remove, kept as one row at the bottom of the settings page. */
@Composable
private fun SourceMaintenanceFooter(
    update: dev.veneranative.data.source.SourceCatalogEntry?,
    installing: Boolean,
    busy: Boolean,
    onUpdate: () -> Unit,
    onUninstall: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End,
    ) {
        if (update?.version != null) {
            TextButton(onClick = onUpdate, enabled = !busy && !installing) {
                Text(
                    text = if (installing) "更新中…" else "更新到 ${update.version}",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
        TextButton(onClick = onUninstall, enabled = !busy) {
            Text(
                text = "移除来源",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
