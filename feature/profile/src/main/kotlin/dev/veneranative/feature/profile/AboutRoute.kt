package dev.veneranative.feature.profile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.veneranative.data.update.SelfUpdateGateway
import java.util.Locale

data class InstalledAppInfo(val versionName: String, val versionCode: Long)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutRoute(info: InstalledAppInfo, gateway: SelfUpdateGateway, onBack: () -> Unit) {
    val model: AboutViewModel = viewModel { AboutViewModel(gateway, info.versionCode) }
    val state by model.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        // Returning from the one-time install-permission settings resumes the update flow.
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) model.onAction(AboutAction.Resume)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    AboutScreen(info, state, model::onAction, onBack)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AboutScreen(
    info: InstalledAppInfo,
    state: AboutUiState,
    onAction: (AboutAction) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("关于") }, navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回我的") }
            })
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Spacer(Modifier.height(24.dp))
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(88.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Text("V", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            Text("Venera Native", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Android 原生漫画阅读器", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                ListItem(headlineContent = { Text("版本") }, trailingContent = { Text(info.versionName) },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow))
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                ListItem(headlineContent = { Text("构建版本") }, trailingContent = { Text(info.versionCode.toString()) },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow))
            }
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                ListItem(
                    headlineContent = { Text("检查更新") },
                    supportingContent = { Text(updateSupportingText(state)) },
                    leadingContent = { Icon(Icons.Default.Refresh, null, tint = MaterialTheme.colorScheme.primary) },
                    modifier = Modifier.fillMaxWidth().testTag("about_check_update").clickable(
                        enabled = state.phase == AboutUpdatePhase.IDLE,
                        onClick = { onAction(AboutAction.CheckForUpdate) },
                    ),
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                )
            }
            Text("受 Venera 启发的非官方原生实现", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    UpdateDialogs(state, onAction)
}

@Composable
private fun UpdateDialogs(state: AboutUiState, onAction: (AboutAction) -> Unit) {
    when (state.phase) {
        AboutUpdatePhase.CONFIRM -> AlertDialog(
            onDismissRequest = { onAction(AboutAction.CancelUpdate) },
            title = { Text("发现新版本") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        state.newVersionName?.let { "v$it" } ?: state.release?.tagName ?: "",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    state.release?.notes?.let { notes ->
                        Text(notes.trim(), maxLines = 12, overflow = TextOverflow.Ellipsis)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { onAction(AboutAction.StartUpdate) }) { Text("立即更新") } },
            dismissButton = { TextButton(onClick = { onAction(AboutAction.CancelUpdate) }) { Text("取消") } },
        )

        AboutUpdatePhase.AWAIT_PERMISSION -> AlertDialog(
            onDismissRequest = { onAction(AboutAction.CancelUpdate) },
            title = { Text("需要安装权限") },
            text = {
                Text(
                    "首次自动更新需要允许本应用安装未知应用。请点击「去授权」并在系统设置中允许，" +
                        "返回后更新会自动继续；之后的更新不再需要这一步。",
                )
            },
            confirmButton = { TextButton(onClick = { onAction(AboutAction.OpenInstallPermission) }) { Text("去授权") } },
            dismissButton = { TextButton(onClick = { onAction(AboutAction.CancelUpdate) }) { Text("取消") } },
        )

        AboutUpdatePhase.DOWNLOADING -> AlertDialog(
            onDismissRequest = { onAction(AboutAction.CancelUpdate) },
            title = { Text("正在下载新版本") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val progress = state.downloadProgress
                    val fraction = progress?.fraction
                    if (fraction != null) LinearProgressIndicator(progress = { fraction })
                    else LinearProgressIndicator()
                    progress?.let { current ->
                        val downloaded = formatBytes(current.bytesDownloaded)
                        val total = current.totalBytes?.let(::formatBytes) ?: "未知"
                        Text("已下载 $downloaded / $total", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            dismissButton = { TextButton(onClick = { onAction(AboutAction.CancelUpdate) }) { Text("取消") } },
            confirmButton = {},
        )

        AboutUpdatePhase.INSTALLING -> AlertDialog(
            onDismissRequest = { onAction(AboutAction.CancelUpdate) },
            title = { Text("正在安装") },
            text = {
                Text("安装确认后应用将自动完成更新并重启；期间无需任何操作。")
            },
            confirmButton = {},
        )

        AboutUpdatePhase.INSTALLED -> AlertDialog(
            onDismissRequest = { onAction(AboutAction.DismissInstalled) },
            title = { Text("更新完成") },
            text = { Text("新版本已安装。如果应用没有自动重启，请重新打开它。") },
            confirmButton = { TextButton(onClick = { onAction(AboutAction.DismissInstalled) }) { Text("好的") } },
        )

        AboutUpdatePhase.IDLE, AboutUpdatePhase.CHECKING -> Unit
    }
}

private fun updateSupportingText(state: AboutUiState): String = when (state.phase) {
    AboutUpdatePhase.CHECKING -> "正在检查…"
    AboutUpdatePhase.CONFIRM, AboutUpdatePhase.AWAIT_PERMISSION ->
        state.newVersionName?.let { "发现新版本 v$it" } ?: "发现新版本"
    AboutUpdatePhase.DOWNLOADING -> state.downloadProgress?.let { progress ->
        val downloaded = formatBytes(progress.bytesDownloaded)
        val total = progress.totalBytes?.let(::formatBytes) ?: "未知"
        "正在下载 $downloaded / $total"
    } ?: "正在下载…"
    AboutUpdatePhase.INSTALLING -> "正在安装…"
    AboutUpdatePhase.INSTALLED -> "已更新到新版本"
    AboutUpdatePhase.IDLE -> when {
        state.problem != null -> problemText(state.problem)
        state.upToDate -> "已是最新版本"
        else -> "从 GitHub 检查并自动安装新版本"
    }
}

private fun problemText(problem: AboutUpdateProblem): String = when (problem) {
    AboutUpdateProblem.NETWORK -> "暂时无法连接 GitHub，请稍后重试"
    AboutUpdateProblem.NO_RELEASE -> "还没有发布任何版本"
    AboutUpdateProblem.NO_INSTALLABLE_APK -> "新版本未提供可自动安装的 APK"
    AboutUpdateProblem.DOWNLOAD -> "安装包下载失败，请重试"
    AboutUpdateProblem.INSTALL -> "安装未完成，请重试"
}

private fun formatBytes(bytes: Long): String {
    val megabytes = bytes / 1024.0 / 1024.0
    return if (megabytes >= 1.0) {
        String.format(Locale.US, "%.1f MB", megabytes)
    } else {
        "${bytes / 1024} KB"
    }
}
