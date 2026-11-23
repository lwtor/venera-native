package dev.veneranative.feature.sources

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.veneranative.core.model.ComicKey
import dev.veneranative.core.model.SourceId
import dev.veneranative.data.comic.CatalogComicMatch
import dev.veneranative.data.comic.CatalogProbeFailureStage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComicSourceSearchScreen(
    state: SourcesUiState,
    onAction: (SourcesAction) -> Unit,
    onBack: () -> Unit,
    onOpenComic: (ComicKey) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("按漫画查找来源") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回漫画源")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(
                "只检查当前目录。找到后再添加需要的来源。",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = state.comicQuery,
                    onValueChange = { onAction(SourcesAction.ComicQueryChanged(it)) },
                    modifier = Modifier.weight(1f),
                    label = { Text("漫画名称") },
                    singleLine = true,
                    enabled = !state.comicSearchLoading,
                )
                Button(
                    onClick = { onAction(SourcesAction.SearchCatalogForComic) },
                    enabled = state.comicQuery.isNotBlank() && state.catalogStatus == CatalogStatus.Ready && !state.comicSearchLoading,
                ) { Text("搜索") }
            }

            if (state.comicSearchLoading) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(
                        "正在检查 ${state.comicSearchCompletedSources}/${state.comicSearchTotalSources} 个来源",
                        Modifier.weight(1f).padding(start = 10.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = { onAction(SourcesAction.CancelCatalogComicSearch) }) { Text("取消") }
                }
            } else if (state.comicSearchPerformed) {
                SearchSummary(state)
            }

            if (state.comicSearchMatches.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.comicSearchMatches, key = { "${it.source.scriptUrl}:${it.comic.key.remoteId.value}" }) { match ->
                        MatchCard(
                            match = match,
                            state = state,
                            onInstall = { onAction(SourcesAction.InstallComicMatch(match)) },
                            onEnable = { onAction(SourcesAction.SetEnabled(it, true)) },
                            onOpenComic = onOpenComic,
                        )
                    }
                }
            } else if (state.comicSearchPerformed && !state.comicSearchLoading) {
                val message = if (state.comicSearchTotalSources == 0) {
                    "当前目录没有可搜索的来源。"
                } else if (state.comicSearchFailedSources > 0) {
                    "没有找到可阅读的结果。${state.comicSearchFailedSources}/${state.comicSearchTotalSources} 个来源试搜失败；失败阶段见上方统计。"
                } else {
                    "检查了 ${state.comicSearchTotalSources} 个来源，没有发现可阅读的搜索结果。"
                }
                Text(message, Modifier.padding(horizontal = 16.dp, vertical = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SearchSummary(state: SourcesUiState) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            if (state.comicSearchFailedSources == 0) "找到 ${state.comicSearchMatches.size} 个来源"
            else "找到 ${state.comicSearchMatches.size} 个来源；${state.comicSearchFailedSources} 个来源试搜失败",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (state.comicSearchFailuresByStage.isNotEmpty()) {
            val labels = mapOf(
                CatalogProbeFailureStage.RuntimeUnavailable to "运行时不可用",
                CatalogProbeFailureStage.PrepareSource to "获取或读取来源脚本失败",
                CatalogProbeFailureStage.InstallSource to "来源脚本安装失败",
                CatalogProbeFailureStage.Search to "搜索请求失败",
                CatalogProbeFailureStage.LoadDetails to "漫画详情加载失败",
                CatalogProbeFailureStage.NoChapters to "结果没有可读章节",
                CatalogProbeFailureStage.Timeout to "探测超时",
            )
            Text(
                state.comicSearchFailuresByStage.entries.joinToString(" · ") { (stage, count) -> "${labels[stage]} $count" },
                modifier = Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun MatchCard(
    match: CatalogComicMatch,
    state: SourcesUiState,
    onInstall: () -> Unit,
    onEnable: (SourceId) -> Unit,
    onOpenComic: (ComicKey) -> Unit,
) {
    val installed = state.sources.firstOrNull { it.sourceId == match.comic.key.sourceId }
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(match.comic.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                Text(match.source.name, style = MaterialTheme.typography.bodyMedium)
                Text("版本 ${match.source.version ?: "未知"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            when {
                installed != null && installed.catalogLocation == match.source.catalogLocation && installed.version != match.source.version ->
                    TextButton(onClick = onInstall, enabled = !state.installing) { Text("更新") }
                installed?.enabled == true && installed.catalogLocation == match.source.catalogLocation ->
                    TextButton(onClick = { onOpenComic(match.comic.key) }) { Text("打开") }
                installed != null && installed.catalogLocation != match.source.catalogLocation ->
                    TextButton(onClick = onInstall, enabled = !state.installing) { Text("切换") }
                installed?.enabled == false ->
                    TextButton(onClick = { onEnable(installed.sourceId) }, enabled = installed.sourceId !in state.busySourceIds) { Text("启用") }
                installed != null -> Text("已安装", style = MaterialTheme.typography.labelSmall)
                else -> Button(onClick = onInstall, enabled = !state.installing) { Text(if (state.installing) "添加中" else "添加") }
            }
        }
    }
}
