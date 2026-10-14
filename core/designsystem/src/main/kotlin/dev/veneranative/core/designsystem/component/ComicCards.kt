package dev.veneranative.core.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.veneranative.core.image.ComicImageRequest
import dev.veneranative.core.image.compose.ComicImageWithContent

/** Names supplied by root assembly, including disabled installed sources. No repository in UI. */
val LocalComicSourceNames = compositionLocalOf<Map<String, String>> { emptyMap() }

@Composable
fun comicSourceLabel(sourceId: String?): String =
    sourceId?.let { LocalComicSourceNames.current[it] ?: it } ?: "本地"

@Composable
fun ComicCornerBadge(text: String, modifier: Modifier = Modifier, color: Color = Color.Black.copy(alpha = 0.72f)) {
    Surface(modifier, shape = RoundedCornerShape(bottomStart = 4.dp), color = color) {
        Text(text, Modifier.padding(horizontal = 3.dp, vertical = 1.dp),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 12.sp),
            color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** One connected card: top-rounded cover, straight seam, bottom-rounded two-line footer. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ComicCoverCard(
    title: String,
    request: ComicImageRequest?,
    chapterLabel: String,
    sourceLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    hasUpdate: Boolean = false,
) {
    Surface(modifier = modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)),
        shadowElevation = 1.dp) {
        Box {
            // This layer follows the foreground's height instead of stretching a lazy-grid item.
            ComicImageWithContent(request = request, contentDescription = title, modifier = Modifier.matchParentSize(),
                placeholder = { ComicCoverPlaceholder(title) },
                imageContent = { painter ->
                    Box(Modifier.fillMaxSize()) {
                        Column {
                            Image(painter, title, Modifier.fillMaxWidth().aspectRatio(0.9f), contentScale = ContentScale.Crop)
                            Image(painter, null, contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxWidth().height(42.dp).clipToBounds()
                                    .blur(16.dp, BlurredEdgeTreatment.Unbounded)
                                    .graphicsLayer { alpha = if (android.os.Build.VERSION.SDK_INT >= 31) 0.3f else 0.12f })
                        }
                    }
                })
            Column {
                Box(Modifier.fillMaxWidth().aspectRatio(0.9f)) {
                    ComicCornerBadge(sourceLabel, Modifier.align(Alignment.TopEnd).widthIn(max = 80.dp))
                    if (hasUpdate) Surface(modifier = Modifier.align(Alignment.TopStart),
                        shape = RoundedCornerShape(bottomEnd = 4.dp), color = MaterialTheme.colorScheme.error) {
                        Text("更新", Modifier.padding(horizontal = 3.dp, vertical = 1.dp), color = MaterialTheme.colorScheme.onError,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 12.sp))
                    }
                }
                Column(Modifier.fillMaxWidth().height(42.dp).padding(horizontal = 7.dp, vertical = 5.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 16.sp),
                        color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val chapterStyle = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 14.sp)
                    val parts = chapterLabel.split('/', limit = 2)
                    if (parts.size == 2) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(parts[0], Modifier.weight(1f, fill = false), style = chapterStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("/${parts[1]}", style = chapterStyle, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    } else Text(chapterLabel, style = chapterStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** Fixed cover-height list row, with shared backdrop and slots for contextual information/actions. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ComicHorizontalCard(
    title: String,
    request: ComicImageRequest?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: String? = null,
    badgeColor: Color = Color.Black.copy(alpha = 0.72f),
    supportingContent: @Composable ColumnScope.() -> Unit = {},
    bottomContent: @Composable () -> Unit = {},
    trailingContent: @Composable () -> Unit = {},
) {
    Surface(modifier = modifier.fillMaxWidth().combinedClickable(onClick = onClick), shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))) {
        Box(Modifier.fillMaxWidth().height(120.dp).clipToBounds()) {
            ComicImageWithContent(request = request, contentDescription = null, modifier = Modifier.fillMaxSize(),
                placeholder = { Box(Modifier.size(80.dp, 120.dp)) { ComicCoverPlaceholder(title) } },
                imageContent = { painter ->
                    val opacity = if (android.os.Build.VERSION.SDK_INT >= 31) 0.9f else 0.08f
                    // Isolate the cover from the image scope's full-row minimum constraints.
                    Box(Modifier.fillMaxSize()) {
                        Image(painter, contentDescription = null, contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                                .drawWithCache {
                                    val mask = Brush.horizontalGradient(0f to Color.White.copy(alpha = 0.03f),
                                        0.28f to Color.White.copy(alpha = 0.08f), 1f to Color.White.copy(alpha = opacity))
                                    onDrawWithContent { drawContent(); drawRect(mask, blendMode = BlendMode.DstIn) }
                                }.blur(20.dp, BlurredEdgeTreatment.Unbounded))
                        Image(painter, title, Modifier.size(80.dp, 120.dp).align(Alignment.CenterStart), contentScale = ContentScale.Crop)
                    }
                })
            Row(Modifier.fillMaxSize().padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Spacer(Modifier.size(80.dp, 120.dp))
                Column(Modifier.weight(1f).height(120.dp).padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    supportingContent()
                    Spacer(Modifier.weight(1f))
                    bottomContent()
                }
                trailingContent()
            }
            badge?.let { Box(Modifier.size(80.dp, 120.dp)) { ComicCornerBadge(it, Modifier.align(Alignment.TopEnd), badgeColor) } }
        }
    }
}

@Composable
private fun ComicCoverPlaceholder(title: String) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer), contentAlignment = Alignment.Center) {
        Text(title.take(2), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
