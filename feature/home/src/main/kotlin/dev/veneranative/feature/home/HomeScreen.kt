package dev.veneranative.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.veneranative.core.designsystem.VeneraNativeTheme
import dev.veneranative.core.image.ComicImageRequest
import dev.veneranative.core.image.compose.ComicImage
import dev.veneranative.core.model.comicKeyOrNull
import dev.veneranative.data.collection.CollectionRepository
import dev.veneranative.data.history.HistoryRepository
import dev.veneranative.data.history.ReadingHistoryEntry
import dev.veneranative.data.local.LocalComicRepository

private const val HOME_COVER_VARIANT = "home-cover"

@Composable
fun HomeRoute(
    history: HistoryRepository?,
    collection: CollectionRepository?,
    localRepository: LocalComicRepository?,
    onResumeReading: (ReadingHistoryEntry) -> Unit,
    onOpenSources: () -> Unit,
    onOpenExplore: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenLibrary: () -> Unit,
) {
    val viewModelKey = "home-${history != null}-${collection != null}-${localRepository != null}"
    val homeViewModel: HomeViewModel = viewModel(key = viewModelKey) {
        HomeViewModel(history, collection, localRepository)
    }
    val state by homeViewModel.state.collectAsStateWithLifecycle()

    HomeScreen(
        state = state,
        onResumeReading = onResumeReading,
        onOpenSources = onOpenSources,
        onOpenExplore = onOpenExplore,
        onOpenSearch = onOpenSearch,
        onOpenLibrary = onOpenLibrary,
    )
}

@Composable
internal fun HomeScreen(
    state: HomeUiState,
    onResumeReading: (ReadingHistoryEntry) -> Unit,
    onOpenSources: () -> Unit,
    onOpenExplore: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenLibrary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            item {
                Column(
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    HomeHeader(onOpenSources = onOpenSources)
                    SearchEntry(onOpenSearch = onOpenSearch)
                    WelcomeCard(onOpenExplore = onOpenExplore, onOpenSearch = onOpenSearch)
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionHeader(
                        title = "接着阅读",
                        action = null,
                        onClick = onOpenLibrary,
                    )
                    val latest = state.recentReading.firstOrNull()
                    if (latest == null) {
                        EmptyResumeCard(
                            waitingForData = !state.historyAvailable,
                            onOpenExplore = onOpenExplore,
                        )
                    } else {
                        ResumeCard(entry = latest, onClick = { onResumeReading(latest) })
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val updated = state.favorites.filter { it.hasUpdate }
                    SectionHeader(
                        title = "收藏更新",
                        action = "书架",
                        onClick = onOpenLibrary,
                    )
                    if (updated.isEmpty()) {
                        EmptySectionCard(
                            title = when {
                                !state.favoritesAvailable -> "正在读取收藏"
                                state.favorites.isEmpty() -> "收藏的漫画会出现在这里"
                                else -> "有新章节时会在这里提醒你"
                            },
                            subtitle = if (state.favorites.isEmpty()) "先去探索，收藏喜欢的作品" else "检查更新后，你追的作品会显示在这里",
                            action = if (state.favorites.isEmpty()) "去探索" else "打开书架",
                            onClick = if (state.favorites.isEmpty()) onOpenExplore else onOpenLibrary,
                        )
                    } else {
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Spacer(Modifier.width(8.dp))
                            updated.take(8).forEach { favorite ->
                                FavoriteUpdateCard(
                                    item = favorite,
                                    onClick = onOpenLibrary,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                        }
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionHeader(
                        title = "本地漫画",
                        action = "管理",
                        onClick = onOpenLibrary,
                    )
                    LocalLibraryCard(
                        count = state.localComics.size,
                        isAvailable = state.localLibraryAvailable,
                        onClick = onOpenLibrary,
                    )
                }
            }
            item {
                Column(
                    modifier = Modifier.padding(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SectionHeader(title = "发现更多", action = null, onClick = {})
                    DiscoveryCard(
                        onExplore = onOpenExplore,
                        onAddSource = onOpenSources,
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeHeader(onOpenSources: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(46.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text("V", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleLarge)
            }
        }
        Column(modifier = Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text("Venera", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("漫画，随时继续", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onOpenSources) { Text("来源") }
    }
}

@Composable
private fun SearchEntry(onOpenSearch: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onOpenSearch),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(18.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("⌕", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
            Text("搜索漫画、作者或来源", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text("搜索", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun WelcomeCard(onOpenExplore: () -> Unit, onOpenSearch: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF38234E)),
    ) {
        Box {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 18.dp, top = 20.dp)
                    .size(112.dp)
                    .background(Color.White.copy(alpha = 0.07f), CircleShape),
            )
            Column(
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 22.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("你的下一段漫画旅程", style = MaterialTheme.typography.labelLarge, color = Color(0xFFDCC7F1))
                Text(
                    "打开书架，\n也发现新故事。",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = onOpenExplore) { Text("开始探索") }
                    TextButton(onClick = onOpenSearch) { Text("搜索漫画", color = Color.White) }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, action: String?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        if (action != null) TextButton(onClick = onClick) { Text(action) }
    }
}

@Composable
private fun EmptyResumeCard(waitingForData: Boolean, onOpenExplore: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Surface(
                modifier = Modifier.size(width = 76.dp, height = 92.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("读", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    if (waitingForData) "正在整理阅读记录" else "还没有阅读记录",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text("从喜欢的故事开始，进度会自动保存在这里。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onOpenExplore) { Text("去发现漫画  ›") }
            }
        }
    }
}

@Composable
private fun ResumeCard(entry: ReadingHistoryEntry, onClick: () -> Unit) {
    val request = entry.coverUrl?.let {
        ComicImageRequest(url = it, sourceId = entry.comicKey.sourceId, variant = HOME_COVER_VARIANT)
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ComicImage(
                request = request,
                contentDescription = entry.comicTitle,
                modifier = Modifier
                    .size(width = 78.dp, height = 108.dp)
                    .clip(RoundedCornerShape(14.dp)),
                contentScale = ContentScale.Crop,
                placeholder = { CoverPlaceholder(entry.comicTitle) },
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(entry.comicTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(entry.chapterTitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val progress = if (entry.pageCount > 0) (entry.pageIndex + 1f) / entry.pageCount else 0f
                LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Text("第 ${entry.pageIndex + 1} / ${entry.pageCount.coerceAtLeast(1)} 页", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("继续阅读  ›", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun EmptySectionCard(title: String, subtitle: String, action: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(action, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun FavoriteUpdateCard(item: dev.veneranative.data.collection.FavoriteItem, onClick: () -> Unit) {
    val comicKey = item.ref.comicKeyOrNull()
    val coverRef = item.coverRef
    val request = if (comicKey != null && coverRef != null) {
        ComicImageRequest(url = coverRef, sourceId = comicKey.sourceId, variant = HOME_COVER_VARIANT)
    } else null
    Card(
        modifier = Modifier
            .width(138.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column {
            ComicImage(
                request = request,
                contentDescription = item.title,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(124.dp),
                contentScale = ContentScale.Crop,
                placeholder = { CoverPlaceholder(item.title) },
            )
            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(item.title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("有新章节", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun CoverPlaceholder(title: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = title.take(2),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(8.dp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun LocalLibraryCard(count: Int, isAvailable: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Surface(modifier = Modifier.size(48.dp), shape = RoundedCornerShape(15.dp), color = MaterialTheme.colorScheme.tertiaryContainer) {
                Box(contentAlignment = Alignment.Center) {
                    Text("▧", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("设备上的漫画", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    when {
                        !isAvailable -> "正在读取本地书架"
                        count == 0 -> "导入文件夹或 ZIP / CBZ / 7z"
                        count == 1 -> "已导入 1 部漫画"
                        else -> "已导入 $count 部漫画"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text("打开  ›", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun DiscoveryCard(onExplore: () -> Unit, onAddSource: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        QuickActionCard(
            modifier = Modifier.weight(1f),
            title = "按来源探索",
            subtitle = "逛逛漫画源",
            glyph = "◉",
            onClick = onExplore,
        )
        QuickActionCard(
            modifier = Modifier.weight(1f),
            title = "添加来源",
            subtitle = "连接你的漫画源",
            glyph = "+",
            onClick = onAddSource,
        )
    }
}

@Composable
private fun QuickActionCard(modifier: Modifier = Modifier, title: String, subtitle: String, glyph: String, onClick: () -> Unit) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(glyph, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSecondaryContainer)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Preview(showBackground = true, widthDp = 393, heightDp = 852)
@Composable
private fun HomeScreenPreview() {
    VeneraNativeTheme {
        HomeScreen(
            state = HomeUiState(historyAvailable = true, favoritesAvailable = true, localLibraryAvailable = true),
            onResumeReading = {},
            onOpenSources = {},
            onOpenExplore = {},
            onOpenSearch = {},
            onOpenLibrary = {},
        )
    }
}
