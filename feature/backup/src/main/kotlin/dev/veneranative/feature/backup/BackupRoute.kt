package dev.veneranative.feature.backup

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.veneranative.core.backup.BackupCategory
import dev.veneranative.data.backup.GitHubBackupGatewayFactory

@Composable
fun BackupRoute(factory: GitHubBackupGatewayFactory?, onBack: () -> Unit) {
    val screenViewModel: BackupViewModel = viewModel(key = "backup-${factory != null}") { BackupViewModel(factory) }
    val state by screenViewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TextButton(onClick = onBack) { Text("‹ 返回") }
        Text("GitHub 数据备份", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("只备份你勾选的结构化数据，不会上传漫画图片、下载文件或缓存。备份在上传前会使用你的密码加密；忘记密码后无法恢复。GitHub App 需要启用 Device Flow，并授予私有仓库创建权限和 Contents 读写权限。")

        OutlinedTextField(
            value = state.password,
            onValueChange = { screenViewModel.onAction(BackupAction.PasswordChanged(it)) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("备份加密密码（至少 8 位）") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
        )

        Text("备份内容", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        BackupCategory.entries.forEach { category ->
            val label = when (category) {
                BackupCategory.Favorites -> "收藏夹和收藏漫画"
                BackupCategory.ReadingHistory -> "浏览记录和阅读进度"
                BackupCategory.AppPreferences -> "阅读与显示偏好"
            }
            androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(
                    checked = category in state.categories,
                    onCheckedChange = { screenViewModel.onAction(BackupAction.CategoryToggled(category)) },
                )
                Text(label)
            }
        }

        state.authorization?.let { authorization ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("等待 GitHub 授权", fontWeight = FontWeight.SemiBold)
                    Text("授权码：${authorization.userCode}", style = MaterialTheme.typography.headlineSmall)
                    Button(onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(authorization.verificationUri)))
                    }) { Text("打开 GitHub 输入授权码") }
                    Text("授权页显示的是 GitHub 官方域名 github.com；确认后返回此页面等待连接完成。")
                }
            }
        }
        state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (state.busy) CircularProgressIndicator()
        if (state.connected) {
            Button(
                onClick = { screenViewModel.onAction(BackupAction.CreateBackup) },
                enabled = !state.busy && state.password.length >= 8 && state.categories.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("立即备份") }
            OutlinedButton(
                onClick = { screenViewModel.onAction(BackupAction.Restore) },
                enabled = !state.busy && state.password.length >= 8 && state.categories.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("从 GitHub 恢复（合并到本机）") }
            TextButton(onClick = { screenViewModel.onAction(BackupAction.Disconnect) }) { Text("断开 GitHub") }
        } else {
            Button(
                onClick = { screenViewModel.onAction(BackupAction.Connect) },
                enabled = !state.busy && factory != null,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("连接 GitHub") }
            if (factory == null) Text("正在准备本地数据……")
        }
    }
}
