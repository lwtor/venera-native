package dev.veneranative.feature.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Card
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.veneranative.core.designsystem.VeneraNativeTheme
import dev.veneranative.core.model.ComicKey
import dev.veneranative.core.model.ComicRef
import dev.veneranative.core.model.LocalComicId
import dev.veneranative.data.collection.FavoriteFolder
import dev.veneranative.data.collection.FavoriteItem
import dev.veneranative.data.collection.ShelfSort
import dev.veneranative.feature.library.component.FavoriteGrid
import dev.veneranative.feature.library.component.FolderChips

/**
 * The library screen: the favourites shelf.
 *
 * It renders [LibraryUiState] and sends [LibraryAction]s and nothing else — no repository, no
 * sorting, no dialog state of its own. Folder management acts on the selected folder through a
 * compact menu, while long-pressing a comic opens its contextual action sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibraryScreen(
    state: LibraryUiState,
    onAction: (LibraryAction) -> Unit,
    onOpenComic: (ComicKey) -> Unit,
    modifier: Modifier = Modifier,
    onOpenLocalChapter: (LocalComicId, dev.veneranative.core.model.LocalChapterId) -> Unit = { _, _ -> },
) {
    val selectedFolder = state.folders.firstOrNull { it.id == state.selectedFolderId }
    BackHandler(enabled = state.tab != LibraryTab.Favorites) {
        onAction(LibraryAction.SelectTab(LibraryTab.Favorites))
    }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(if (state.tab == LibraryTab.Favorites) "书架" else state.tab.title()) },
                    windowInsets = WindowInsets(0.dp),
                    navigationIcon = {
                        if (state.tab != LibraryTab.Favorites) {
                            IconButton(onClick = { onAction(LibraryAction.SelectTab(LibraryTab.Favorites)) }) {
                                Icon(Icons.Filled.ArrowBack, contentDescription = "返回书架")
                            }
                        }
                    },
                    actions = {
                        if (state.tab == LibraryTab.Favorites) {
                            IconButton(onClick = { onAction(LibraryAction.ToggleFavoriteSearch) }) {
                                Icon(
                                    imageVector = Icons.Filled.Search,
                                    contentDescription = if (state.favoriteSearchVisible) "关闭搜索" else "搜索收藏",
                                )
                            }
                            IconButton(onClick = { onAction(LibraryAction.RefreshUpdates) }) {
                                Icon(Icons.Filled.Refresh, contentDescription = "检查书架更新")
                            }
                        } else if (state.tab == LibraryTab.Local) {
                            IconButton(onClick = { onAction(LibraryAction.RequestLocalImport) }) {
                                Icon(Icons.Filled.Add, contentDescription = "导入本地文件夹")
                            }
                            var importMenuExpanded by remember { mutableStateOf(false) }
                            Box {
                                IconButton(onClick = { importMenuExpanded = true }) {
                                    Icon(Icons.Filled.MoreVert, contentDescription = "更多导入选项")
                                }
                                DropdownMenu(expanded = importMenuExpanded, onDismissRequest = { importMenuExpanded = false }) {
                                    DropdownMenuItem(
                                        text = { Text("导入 CBZ / ZIP / 7z") },
                                        onClick = {
                                            importMenuExpanded = false
                                            onAction(LibraryAction.RequestArchiveImport)
                                        },
                                    )
                                }
                            }
                        }
                        if (state.tab == LibraryTab.Favorites) {
                            var libraryMenuExpanded by remember { mutableStateOf(false) }
                            Box {
                                IconButton(onClick = { libraryMenuExpanded = true }) {
                                    Icon(Icons.Filled.MoreVert, contentDescription = "书架其他内容")
                                }
                                DropdownMenu(
                                    expanded = libraryMenuExpanded,
                                    onDismissRequest = { libraryMenuExpanded = false },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("下载 · ${state.downloads.size}") },
                                        onClick = {
                                            libraryMenuExpanded = false
                                            onAction(LibraryAction.SelectTab(LibraryTab.Downloads))
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("本地漫画 · ${state.localComics.size}") },
                                        onClick = {
                                            libraryMenuExpanded = false
                                            onAction(LibraryAction.SelectTab(LibraryTab.Local))
                                        },
                                    )
                                }
                            }
                        }
                    }
                )
            }
        },
    ) { contentPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
            state.message?.let { message ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { onAction(LibraryAction.DismissMessage) }) { Text("确定") }
                }
            }

            if (state.tab == LibraryTab.Favorites) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FolderChips(
                        folders = state.folders,
                        selectedFolderId = state.selectedFolderId,
                        onSelect = { folderId -> onAction(LibraryAction.SelectFolder(folderId)) },
                        modifier = Modifier.weight(1f).height(56.dp),
                    )
                    var folderMenuExpanded by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { folderMenuExpanded = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "收藏夹管理")
                        }
                        DropdownMenu(expanded = folderMenuExpanded, onDismissRequest = { folderMenuExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text("新建收藏夹") },
                                onClick = {
                                    folderMenuExpanded = false
                                    onAction(LibraryAction.EditFolder(null))
                                },
                            )
                            DropdownMenuItem(
                                enabled = selectedFolder != null,
                                text = { Text("重命名当前收藏夹") },
                                onClick = {
                                    folderMenuExpanded = false
                                    selectedFolder?.let { onAction(LibraryAction.EditFolder(it.id)) }
                                },
                            )
                            DropdownMenuItem(
                                enabled = selectedFolder?.removable == true,
                                text = { Text("删除当前收藏夹") },
                                onClick = {
                                    folderMenuExpanded = false
                                    selectedFolder?.let { onAction(LibraryAction.DeleteFolder(it.id)) }
                                },
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("${state.filteredItems.size} 部漫画", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ShelfSortMenu(sort = state.sort, onSortChange = { onAction(LibraryAction.ChangeSort(it)) })
                }

                if (state.favoriteSearchVisible) {
                    OutlinedTextField(
                        value = state.favoriteQuery,
                        onValueChange = { onAction(LibraryAction.FavoriteQueryChanged(it)) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        singleLine = true,
                        label = { Text("搜索收藏") },
                        placeholder = { Text("漫画名称或副标题") },
                    )
                }

                when {
                    state.status == LibraryStatus.Loading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    state.status == LibraryStatus.Empty -> EmptyLibraryState(
                        if (state.selectedFolderId == null) "书架还是空的。" else "这个收藏夹还没有漫画。",
                        if (state.selectedFolderId == null) "收藏的漫画会显示在这里。" else "长按书架中的漫画，可以加入这个收藏夹。",
                        Modifier.weight(1f),
                    )
                    state.status == LibraryStatus.Failed -> Column(
                        modifier = Modifier.weight(1f).fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                    ) {
                        Text("无法读取书架内容。")
                        Button(onClick = { onAction(LibraryAction.Retry) }) { Text("重试") }
                    }
                    state.filteredItems.isEmpty() -> EmptyLibraryState("没有匹配的漫画。", "换一个作品名或副标题试试。", Modifier.weight(1f))
                    else -> FavoriteGrid(
                        items = state.filteredItems,
                        onOpenComic = onOpenComic,
                        onLongPress = { ref -> onAction(LibraryAction.ShowFavoriteActions(ref)) },
                        modifier = Modifier.weight(1f),
                    )
                }
            } else if (state.tab == LibraryTab.Local) {
                if (state.localComics.isEmpty()) {
                    EmptyLibraryState("尚未导入本地漫画。", "导入文件夹或 CBZ / ZIP / 7z 开始阅读。", Modifier.weight(1f))
                } else LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.localComics, key = { it.id.value }) { comic ->
                        LocalComicCard(
                            comic = comic,
                            chapters = state.localChapters[comic.id].orEmpty(),
                            onOpenChapter = { chapter -> onOpenLocalChapter(comic.id, chapter.id) },
                            onRemove = { onAction(LibraryAction.RemoveLocalComic(comic.id)) },
                        )
                    }
                }
            } else {
                if (state.downloads.isEmpty()) {
                    EmptyLibraryState("暂无下载任务。", "从漫画详情页选择章节即可开始下载。", Modifier.weight(1f))
                } else LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.downloads, key = { it.chapter.toString() }) { task ->
                        DownloadTaskCard(
                            task = task,
                            onPause = { onAction(LibraryAction.PauseDownload(task.chapter)) },
                            onResume = { onAction(LibraryAction.ResumeDownload(task.chapter)) },
                            onRetry = { onAction(LibraryAction.RetryDownload(task.chapter)) },
                            onRemove = { onAction(LibraryAction.CancelDownload(task.chapter)) },
                        )
                    }
                }
            }
        }
    }

    state.selectedFavorite?.let { item ->
        ModalBottomSheet(onDismissRequest = { onAction(LibraryAction.DismissFavoriteActions) }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(item.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("加入收藏夹", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("全部 · 始终包含这部漫画", style = MaterialTheme.typography.bodyMedium)
                if (state.folders.isEmpty()) {
                    Text("还没有其他收藏夹，可以新建一个。", style = MaterialTheme.typography.bodyMedium)
                } else {
                    state.folders.forEach { folder ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { onAction(LibraryAction.ToggleFavoriteFolder(folder.id)) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = folder.id in state.selectedFavoriteFolders,
                                onCheckedChange = { onAction(LibraryAction.ToggleFavoriteFolder(folder.id)) },
                            )
                            Text(folder.name)
                        }
                    }
                }
                TextButton(onClick = { onAction(LibraryAction.EditFolder(null)) }) { Text("新建收藏夹") }
                Button(
                    onClick = { onAction(LibraryAction.SaveFavoriteFolders) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("保存收藏夹") }
                HorizontalDivider()
                if (item.hasUpdate) {
                    TextButton(
                        onClick = { onAction(LibraryAction.ClearUpdate(item.ref)) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("标记更新已读", modifier = Modifier.weight(1f))
                    }
                }
                TextButton(
                    onClick = { onAction(LibraryAction.RequestRemoveFavorite) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("从书架移除", modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (state.confirmRemoveFavorite) {
        AlertDialog(
            onDismissRequest = { onAction(LibraryAction.DismissRemoveFavorite) },
            title = { Text("从书架移除？") },
            text = { Text("这部漫画将从全部和所有收藏夹中移除，阅读记录不会删除。") },
            confirmButton = {
                TextButton(onClick = { onAction(LibraryAction.ConfirmRemoveFavorite) }) { Text("移出书架") }
            },
            dismissButton = {
                TextButton(onClick = { onAction(LibraryAction.DismissRemoveFavorite) }) { Text("取消") }
            },
        )
    }

    state.folderEditor?.let { editor ->
        AlertDialog(
            onDismissRequest = { onAction(LibraryAction.DismissFolderEditor) },
            title = { Text(if (editor.folderId == null) "新建收藏夹" else "重命名收藏夹") },
            text = {
                OutlinedTextField(
                    value = editor.draft,
                    onValueChange = { draft -> onAction(LibraryAction.FolderDraftChanged(draft)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = { onAction(LibraryAction.ConfirmFolderEditor) }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { onAction(LibraryAction.DismissFolderEditor) }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun EmptyLibraryState(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShelfSortMenu(
    sort: ShelfSort,
    onSortChange: (ShelfSort) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("排序：${sort.label()}", maxLines = 1)
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ShelfSort.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label()) },
                    onClick = {
                        expanded = false
                        onSortChange(option)
                    },
                )
            }
        }
    }
}

@Composable
private fun LocalComicCard(
    comic: dev.veneranative.data.local.LocalComic,
    chapters: List<dev.veneranative.data.local.LocalChapter>,
    onOpenChapter: (dev.veneranative.data.local.LocalChapter) -> Unit,
    onRemove: () -> Unit,
) {
    var expanded by remember(comic.id) { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (comic.kind == dev.veneranative.data.local.LocalKind.Archive) "▣" else "▤", style = MaterialTheme.typography.titleLarge)
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(comic.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${comic.chapterCount} 章 · ${if (comic.kind == dev.veneranative.data.local.LocalKind.Archive) "压缩包" else "文件夹"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(if (expanded) "⌃" else "⌄", style = MaterialTheme.typography.titleMedium)
                Box {
                    IconButton(onClick = { menuExpanded = true }) { Text("⋮", style = MaterialTheme.typography.titleLarge) }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text("从本地移除") },
                            onClick = {
                                menuExpanded = false
                                onRemove()
                            },
                        )
                    }
                }
            }
            if (expanded) {
                HorizontalDivider()
                if (chapters.isEmpty()) {
                    Text("没有可读取的章节。", modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    chapters.forEach { chapter ->
                        TextButton(onClick = { onOpenChapter(chapter) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                            Text(chapter.title, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("阅读")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadTaskCard(
    task: dev.veneranative.data.download.DownloadTask,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
) {
    val progress = if (task.pageCount > 0) {
        (task.completedPages.toFloat() / task.pageCount).coerceIn(0f, 1f)
    } else 0f
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(task.comicTitle ?: task.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(task.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${task.completedPages}/${task.pageCount} 页 · ${task.state.localizedLabel()}",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                when (task.state) {
                    dev.veneranative.data.download.DownloadChapterState.Queued,
                    dev.veneranative.data.download.DownloadChapterState.Running -> TextButton(onClick = onPause) { Text("暂停") }
                    dev.veneranative.data.download.DownloadChapterState.Paused -> TextButton(onClick = onResume) { Text("继续") }
                    dev.veneranative.data.download.DownloadChapterState.Partial,
                    dev.veneranative.data.download.DownloadChapterState.Failed -> TextButton(onClick = onRetry) { Text("重试") }
                    dev.veneranative.data.download.DownloadChapterState.Completed,
                    dev.veneranative.data.download.DownloadChapterState.Canceled -> Unit
                }
                TextButton(onClick = onRemove) { Text("移除") }
            }
        }
    }
}

private fun ShelfSort.label(): String = when (this) {
    ShelfSort.AddedAt -> "最近添加"
    ShelfSort.Title -> "标题"
    ShelfSort.LastRead -> "最近阅读"
    ShelfSort.Updated -> "最近更新"
}

private fun LibraryTab.title(): String = when (this) {
    LibraryTab.Favorites -> "收藏"
    LibraryTab.Downloads -> "下载"
    LibraryTab.Local -> "本地"
}

private fun dev.veneranative.data.download.DownloadChapterState.localizedLabel(): String = when (this) {
    dev.veneranative.data.download.DownloadChapterState.Queued -> "排队中"
    dev.veneranative.data.download.DownloadChapterState.Running -> "下载中"
    dev.veneranative.data.download.DownloadChapterState.Paused -> "已暂停"
    dev.veneranative.data.download.DownloadChapterState.Partial -> "部分完成"
    dev.veneranative.data.download.DownloadChapterState.Failed -> "失败"
    dev.veneranative.data.download.DownloadChapterState.Completed -> "已完成"
    dev.veneranative.data.download.DownloadChapterState.Canceled -> "已取消"
}

@Preview(showBackground = true)
@Composable
private fun LibraryScreenPreview() {
    VeneraNativeTheme {
        LibraryScreen(
            state = LibraryUiState(
                status = LibraryStatus.Ready,
                folders = listOf(
                    FavoriteFolder(id = "reading", name = "Reading", sortOrder = 1, removable = true),
                ),
                items = listOf(
                    FavoriteItem(
                        ref = ComicRef.Local(LocalComicId("local-1")),
                        title = "An imported comic",
                        folderId = "default",
                        addedAtEpochMillis = 1_000L,
                    ),
                ),
            ),
            onAction = {},
            onOpenComic = {},
        )
    }
}
