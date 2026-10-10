package dev.veneranative.feature.backup

import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.veneranative.core.backup.BackupCategory
import dev.veneranative.data.backup.GitHubBackupGatewayFactory

@Composable
fun BackupRoute(factory: GitHubBackupGatewayFactory?, onBack: () -> Unit) {
    val model: BackupViewModel = viewModel(key = "backup-${factory != null}") { BackupViewModel(factory?.create()) }
    val state by model.state.collectAsStateWithLifecycle()
    DisposableEffect(model) { onDispose { model.clearPassword() } }
    BackupScreen(state, model::onAction, onBack)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BackupScreen(state: BackupUiState, onAction: (BackupAction) -> Unit, onBack: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    var showPassword by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("备份与恢复") }, navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回我的") }
            })
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .imePadding().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = colors.primaryContainer)) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("让阅读记录随你同行", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                        color = colors.onPrimaryContainer)
                    Text("所选数据会加密保存到 GitHub 私有仓库。漫画图片、下载文件和来源凭据不会上传。",
                        style = MaterialTheme.typography.bodyMedium, color = colors.onPrimaryContainer)
                }
            }
            if (!state.connected) {
                Text("请先在“我的”连接 GitHub，再开始备份或恢复。", color = colors.onSurfaceVariant)
                Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("前往我的") }
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("选择内容", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerLow)) {
                    BackupCategory.entries.forEachIndexed { index, category ->
                        val title = when (category) {
                            BackupCategory.Favorites -> "收藏与收藏夹"
                            BackupCategory.ReadingHistory -> "阅读记录"
                            BackupCategory.AppPreferences -> "应用偏好"
                        }
                        val subtitle = when (category) {
                            BackupCategory.Favorites -> "收藏漫画、收藏夹及归属"
                            BackupCategory.ReadingHistory -> "已读章节与上次阅读位置"
                            BackupCategory.AppPreferences -> "已保存的页面选择与显示偏好"
                        }
                        ListItem(
                            headlineContent = { Text(title) },
                            supportingContent = { Text(subtitle) },
                            trailingContent = { Checkbox(checked = category in state.categories, onCheckedChange = null, enabled = !state.busy) },
                            modifier = Modifier.toggleable(value = category in state.categories, enabled = !state.busy, role = Role.Checkbox) { onAction(BackupAction.CategoryToggled(category)) },
                            colors = ListItemDefaults.colors(containerColor = colors.surfaceContainerLow),
                        )
                        if (index < BackupCategory.entries.lastIndex) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = colors.outlineVariant.copy(alpha = 0.5f))
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("加密密码", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = state.password, onValueChange = { onAction(BackupAction.PasswordChanged(it)) },
                    modifier = Modifier.fillMaxWidth(), enabled = !state.busy,
                    label = { Text("至少 8 位密码") }, singleLine = true, shape = RoundedCornerShape(16.dp),
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = { TextButton(onClick = { showPassword = !showPassword }) { Text(if (showPassword) "隐藏" else "显示") } },
                )
                Text("恢复时需输入备份使用的密码，请妥善保存。忘记密码将无法恢复。",
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            state.message?.let { FeedbackCard(it, error = false) }
            state.error?.let { FeedbackCard(it, error = true) }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { onAction(BackupAction.CreateBackup) }, enabled = state.canSubmit,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(16.dp),
                ) { Text(if (state.busy && !state.restoring) "正在备份…" else "立即备份") }
                OutlinedButton(
                    onClick = { onAction(BackupAction.RequestRestore) }, enabled = state.canSubmit,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(16.dp),
                ) { Text(if (state.busy && state.restoring) "正在恢复…" else "恢复到本机") }
                if (state.busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("正在处理，请稍候", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
                Text("恢复会合并所选数据，保留本机已有内容。",
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
    if (state.confirmRestore) AlertDialog(
        onDismissRequest = { onAction(BackupAction.CancelRestore) },
        title = { Text("恢复所选数据？") },
        text = { Text("将从 GitHub 下载备份，解密后把所选类别合并到本机。不会清空本机数据，同一条记录将按合并规则更新。") },
        confirmButton = { TextButton(onClick = { onAction(BackupAction.Restore) }) { Text("确认恢复") } },
        dismissButton = { TextButton(onClick = { onAction(BackupAction.CancelRestore) }) { Text("取消") } },
    )
}

@Composable
private fun FeedbackCard(message: String, error: Boolean) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(16.dp), color = if (error) colors.errorContainer else colors.secondaryContainer) {
        Text(message, Modifier.fillMaxWidth().padding(16.dp), color = if (error) colors.onErrorContainer else colors.onSecondaryContainer)
    }
}
