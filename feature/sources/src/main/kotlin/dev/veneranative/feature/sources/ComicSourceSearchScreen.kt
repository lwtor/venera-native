package dev.veneranative.feature.sources

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.veneranative.core.designsystem.component.ComicCoverCard
import dev.veneranative.core.image.ComicImageRequest
import dev.veneranative.core.model.ComicKey
import dev.veneranative.data.comic.CatalogComicMatch
import dev.veneranative.data.comic.CatalogComicSearchRow
import dev.veneranative.data.comic.CatalogProbeFailureStage

@Composable
fun ComicSourceSearchScreen(
    state: SourcesUiState,
    onAction: (SourcesAction) -> Unit,
    onBack: () -> Unit,
    onOpenComic: (ComicKey) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.comicSearchPerformed) {
        CatalogSearchResultsScreen(
            state = state,
            onAction = onAction,
            onOpenComic = onOpenComic,
            onBackToForm = { onAction(SourcesAction.EditCatalogComicSearch) },
            modifier = modifier,
        )
    } else {
        CatalogSearchFormScreen(state, onAction, onBack, modifier)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CatalogSearchFormScreen(
    state: SourcesUiState,
    onAction: (SourcesAction) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("按漫画查找来源") },
                navigationIcon = {
                    androidx.compose.material3.IconButton(onClick = onBack) {
                        androidx.compose.material3.Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回漫画源")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(
                "搜索当前目录中的所有来源。结果会逐个显示，你可以只添加需要的来源。",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
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
                )
                Button(
                    onClick = { onAction(SourcesAction.SearchCatalogForComic) },
                    enabled = state.comicQuery.isNotBlank() && state.catalogStatus == CatalogStatus.Ready,
                ) { Text("搜索") }
            }
            if (state.catalogStatus == CatalogStatus.Loading) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("正在读取漫画源目录…", style = MaterialTheme.typography.bodyMedium)
                }
            } else if (state.catalogStatus == CatalogStatus.Failed) {
                Text("漫画源目录读取失败，请返回目录页刷新后重试。", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CatalogSearchResultsScreen(
    state: SourcesUiState,
    onAction: (SourcesAction) -> Unit,
    onOpenComic: (ComicKey) -> Unit,
    onBackToForm: () -> Unit,
    modifier: Modifier,
) {
    BackHandler(onBack = onBackToForm)
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(state.comicQuery, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    androidx.compose.material3.IconButton(onClick = onBackToForm) {
                        androidx.compose.material3.Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回搜索")
                    }
                },
                actions = {
                    if (state.comicSearchLoading) {
                        TextButton(onClick = { onAction(SourcesAction.CancelCatalogComicSearch) }) { Text("取消") }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (state.comicSearchRows.isEmpty() && !state.comicSearchLoading) {
                Text("当前目录没有可搜索的来源。", Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                    items(state.comicSearchRows, key = { "${it.source.catalogLocation}:${it.source.scriptUrl}" }) { row ->
                        CatalogSourceResults(
                            row = row,
                            state = state,
                            onInstall = { comic -> onAction(SourcesAction.InstallComicMatch(CatalogComicMatch(row.source, comic))) },
                            onOpenComic = onOpenComic,
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun CatalogSourceResults(
    row: CatalogComicSearchRow,
    state: SourcesUiState,
    onInstall: (dev.veneranative.core.model.Comic) -> Unit,
    onOpenComic: (ComicKey) -> Unit,
) {
    val installed = state.installedSourceFor(row.source)
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(row.source.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            CatalogAddButton(row, installed, state.installing) { row.comics.firstOrNull()?.let(onInstall) }
        }
        when {
            row.isLoading -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            row.failureStage != null -> Text(
                "试搜失败：${row.failureStage?.displayName()}",
                Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
            row.isCancelled -> Text("已取消", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
            row.comics.isEmpty() -> Text("没有结果", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
            else -> LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(row.comics, key = { it.key.remoteId.value }) { comic ->
                    ComicCoverCard(
                        title = comic.title,
                        request = comic.coverUrl?.let { ComicImageRequest(it, sourceId = comic.key.sourceId, variant = "comic-cover") },
                        chapterLabel = "章节未知",
                        sourceLabel = row.source.name,
                        onClick = { onOpenComic(comic.key) },
                        modifier = Modifier.width(128.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun CatalogAddButton(
    row: CatalogComicSearchRow,
    installed: dev.veneranative.core.model.InstalledSource?,
    installing: Boolean,
    onAdd: () -> Unit,
) {
    val sameCatalog = installed != null && installed.catalogLocation == row.source.catalogLocation
    when {
        installed != null && sameCatalog && installed.version == row.source.version ->
            Text("已添加", Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        row.comics.isEmpty() -> Unit
        installed != null && sameCatalog -> TextButton(onClick = onAdd, enabled = !installing) { Text(if (installing) "更新中" else "更新") }
        installed != null -> TextButton(onClick = onAdd, enabled = !installing) { Text(if (installing) "切换中" else "切换") }
        else -> Button(onClick = onAdd, enabled = !installing) { Text(if (installing) "添加中" else "添加") }
    }
}

private fun CatalogProbeFailureStage.displayName(): String = when (this) {
    CatalogProbeFailureStage.RuntimeUnavailable -> "脚本运行时不可用"
    CatalogProbeFailureStage.PrepareSource -> "获取或读取来源脚本"
    CatalogProbeFailureStage.InstallSource -> "安装来源脚本"
    CatalogProbeFailureStage.Search -> "搜索请求"
    CatalogProbeFailureStage.Timeout -> "探测超时"
}
