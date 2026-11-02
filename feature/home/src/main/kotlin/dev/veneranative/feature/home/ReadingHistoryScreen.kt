package dev.veneranative.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.veneranative.core.designsystem.component.ComicHorizontalCard
import dev.veneranative.core.designsystem.component.comicSourceLabel
import dev.veneranative.core.image.ComicImageRequest
import dev.veneranative.data.history.HistoryRepository
import dev.veneranative.data.history.ReadingHistoryEntry

@Composable
fun ReadingHistoryRoute(
    history: HistoryRepository?,
    onResumeReading: (ReadingHistoryEntry) -> Unit,
    onBack: () -> Unit,
) {
    if (history == null) {
        ReadingHistoryScreen(state = ReadingHistoryUiState(), available = false, onBack = onBack, onResumeReading = {}, onDelete = {})
        return
    }
    val historyViewModel: ReadingHistoryViewModel = viewModel(key = "reading-history") { ReadingHistoryViewModel(history) }
    val state by historyViewModel.state.collectAsStateWithLifecycle()
    ReadingHistoryScreen(
        state = state,
        available = true,
        onBack = onBack,
        onResumeReading = onResumeReading,
        onDelete = historyViewModel::remove,
        onDismissError = historyViewModel::dismissError,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReadingHistoryScreen(
    state: ReadingHistoryUiState,
    available: Boolean,
    onBack: () -> Unit,
    onResumeReading: (ReadingHistoryEntry) -> Unit,
    onDelete: (ReadingHistoryEntry) -> Unit,
    onDismissError: () -> Unit = {},
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = { Text("阅读记录") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") } },
                windowInsets = WindowInsets(0.dp),
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        if (!available) {
            EmptyHistory("阅读记录暂不可用", Modifier.fillMaxSize().padding(padding))
        } else if (state.entries.isEmpty()) {
            EmptyHistory("暂无阅读记录", Modifier.fillMaxSize().padding(padding))
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.entries, key = { "${it.comicKey.sourceId.value}:${it.comicKey.remoteId.value}" }) { entry ->
                    HistoryRow(
                        entry = entry,
                        deleting = entry.comicKey in state.deleting,
                        onClick = { onResumeReading(entry) },
                        onDelete = { onDelete(entry) },
                    )
                }
            }
        }
    }
    state.error?.let { message ->
        AlertDialog(
            onDismissRequest = onDismissError,
            title = { Text("操作失败") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = onDismissError) { Text("确定") } },
        )
    }
}

@Composable
private fun HistoryRow(
    entry: ReadingHistoryEntry,
    deleting: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val request = entry.coverUrl?.let {
        ComicImageRequest(url = it, sourceId = entry.comicKey.sourceId, variant = "comic-cover", retainedCoverKey = entry.comicKey)
    }
    ComicHorizontalCard(
        title = entry.comicTitle,
        request = request,
        onClick = onClick,
        badge = comicSourceLabel(entry.comicKey.sourceId.value),
        supportingContent = {
            Text(entry.chapterTitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            val progress = if (entry.pageCount > 0) (entry.pageIndex + 1f) / entry.pageCount else 0f
            androidx.compose.material3.LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            Text("第 ${entry.pageIndex + 1} / ${entry.pageCount.coerceAtLeast(1)} 页", style = MaterialTheme.typography.labelSmall)
        },
        bottomContent = { Text("继续阅读  ›", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) },
        trailingContent = {
            if (deleting) CircularProgressIndicator(modifier = Modifier.padding(8.dp), strokeWidth = 2.dp)
            else IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "删除《${entry.comicTitle}》阅读记录", tint = MaterialTheme.colorScheme.error)
            }
        },
    )
}

@Composable
private fun EmptyHistory(message: String, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(message, style = MaterialTheme.typography.titleMedium)
            Text("阅读过的漫画会显示在这里", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
