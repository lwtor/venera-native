package dev.veneranative.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.veneranative.core.image.ComicImageRequest
import dev.veneranative.core.image.compose.ComicImage
import dev.veneranative.data.backup.GitHubBackupGatewayFactory
import dev.veneranative.data.backup.GitHubSyncOperation
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun ProfileRoute(factory: GitHubBackupGatewayFactory?, onOpenBackup: () -> Unit, onOpenAbout: () -> Unit) {
    val model: ProfileViewModel = viewModel(key = "profile-${factory != null}") { ProfileViewModel(factory?.create()) }
    val state by model.state.collectAsStateWithLifecycle()
    LaunchedEffect(model) { model.onAction(ProfileAction.Refresh) }
    ProfileScreen(state, factory != null, model::onAction, onOpenBackup, onOpenAbout)
}

@Composable
internal fun ProfileScreen(
    state: ProfileUiState,
    ready: Boolean,
    onAction: (ProfileAction) -> Unit,
    onOpenBackup: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize().background(colors.background).verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Text("我的", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Card(
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = colors.primaryContainer),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    val profile = state.account.profile
                    ComicImage(
                        request = profile?.avatarUrl?.let { ComicImageRequest(url = it, variant = "github-avatar") },
                        contentDescription = "GitHub 头像",
                        modifier = Modifier.size(72.dp).clip(CircleShape),
                        placeholder = {
                            Box(Modifier.fillMaxSize().background(colors.surface), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.AccountCircle, null, Modifier.size(52.dp), tint = colors.primary)
                            }
                        },
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            profile?.displayName ?: if (state.account.connected) "GitHub 已连接" else "连接你的 GitHub",
                            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                            color = colors.onPrimaryContainer, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            profile?.let { "@${it.login}" } ?: if (state.account.connected) "正在获取账号信息" else "安全保存收藏与阅读进度",
                            style = MaterialTheme.typography.bodyMedium, color = colors.onPrimaryContainer.copy(alpha = 0.75f),
                        )
                    }
                }
                if (state.account.connected) {
                    HorizontalDivider(color = colors.onPrimaryContainer.copy(alpha = 0.12f))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("最近同步", style = MaterialTheme.typography.labelMedium, color = colors.onPrimaryContainer.copy(alpha = 0.7f))
                        val sync = state.account.lastSync
                        Text(
                            sync?.let {
                                val time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
                                    .format(Instant.ofEpochMilli(it.completedAtEpochMillis))
                                "$time · ${if (it.operation == GitHubSyncOperation.Backup) "备份成功" else "恢复成功"}"
                            } ?: "尚未在此设备同步",
                            style = MaterialTheme.typography.bodyLarge, color = colors.onPrimaryContainer,
                        )
                    }
                    Button(onClick = onOpenBackup, modifier = Modifier.fillMaxWidth()) { Text("备份与恢复") }
                } else {
                    Button(
                        onClick = { onAction(ProfileAction.Connect) },
                        enabled = ready && !state.busy && state.authorization == null,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (state.busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Text(if (ready) "连接 GitHub" else "正在准备账号服务…")
                    }
                }
            }
        }
        state.error?.let { error ->
            Surface(shape = RoundedCornerShape(16.dp), color = colors.errorContainer) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(error, color = colors.onErrorContainer)
                    if (state.account.connected) TextButton(onClick = { onAction(ProfileAction.Refresh) }) { Text("重试") }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("设置", style = MaterialTheme.typography.titleSmall, color = colors.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
            Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerLow)) {
                ListItem(
                    headlineContent = { Text("关于 Venera Native") },
                    supportingContent = { Text("版本信息") },
                    leadingContent = { Icon(Icons.Default.Info, null, tint = colors.primary) },
                    trailingContent = { Text("›", style = MaterialTheme.typography.headlineSmall) },
                    modifier = Modifier.fillMaxWidth().testTag("profile_about").clickable(onClick = onOpenAbout),
                    colors = ListItemDefaults.colors(containerColor = colors.surfaceContainerLow),
                )
            }
        }
        if (state.account.connected) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                TextButton(onClick = { onAction(ProfileAction.RequestDisconnect) }) {
                    Text("断开 GitHub", color = colors.onSurfaceVariant)
                }
            }
        }
    }
    state.authorization?.let { authorization ->
        GitHubAuthorizationDialog(authorization, onCancel = { onAction(ProfileAction.CancelAuthorization) })
    }
    if (state.confirmDisconnect) AlertDialog(
        onDismissRequest = { onAction(ProfileAction.CancelDisconnect) },
        title = { Text("断开 GitHub？") },
        text = { Text("将移除此设备保存的授权信息。本地收藏、阅读记录和 GitHub 中的备份会保留。") },
        confirmButton = { TextButton(onClick = { onAction(ProfileAction.Disconnect) }) { Text("断开") } },
        dismissButton = { TextButton(onClick = { onAction(ProfileAction.CancelDisconnect) }) { Text("取消") } },
    )
}
