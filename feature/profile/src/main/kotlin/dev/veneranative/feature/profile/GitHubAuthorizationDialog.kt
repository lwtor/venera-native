package dev.veneranative.feature.profile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.veneranative.data.backup.GitHubDeviceAuthorization

@Composable
internal fun GitHubAuthorizationDialog(authorization: GitHubDeviceAuthorization, onCancel: () -> Unit) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var copied by remember(authorization.userCode) { mutableStateOf(false) }
    var browserError by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("连接 GitHub") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("复制授权码，在 GitHub 确认授权后返回应用。")
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                    Text(authorization.userCode, Modifier.fillMaxWidth().padding(16.dp),
                        style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                }
                OutlinedButton(onClick = {
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("GitHub 授权码", authorization.userCode))
                    copied = true
                }, modifier = Modifier.fillMaxWidth()) { Text(if (copied) "已复制授权码" else "复制授权码") }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text("等待授权确认", style = MaterialTheme.typography.bodySmall)
                }
                if (browserError) Text("无法打开浏览器，请访问 github.com/login/device", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = { browserError = runCatching { uriHandler.openUri(authorization.verificationUri) }.isFailure }) {
                Text("打开 GitHub")
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("取消") } },
    )
}
