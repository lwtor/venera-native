package dev.veneranative.feature.sources

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.veneranative.core.model.InstalledSourceConfiguration
import dev.veneranative.core.model.SourceSettingDefinition

@Composable
internal fun SourceConfigurationPanel(
    configuration: InstalledSourceConfiguration?,
    state: SourcesUiState,
    onAction: (SourcesAction) -> Unit,
) {
    if (configuration == null) {
        if (state.configurationLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        return
    }
    var values by remember(configuration.source.sourceId.value, configuration.settingValues) {
        mutableStateOf(configuration.settingValues.toMap())
    }
    var account by remember(configuration.source.sourceId.value) { mutableStateOf("") }
    var showCredentialFields by remember(configuration.source.sourceId.value, configuration.hasSavedLoginCredentials) {
        mutableStateOf(!configuration.hasSavedLoginCredentials)
    }
    // Credentials deliberately use ordinary in-memory Compose state, never rememberSaveable.
    var password by remember(configuration.source.sourceId.value) { mutableStateOf("") }
    val uriHandler = LocalUriHandler.current
    val hasEditableSettings = configuration.settings.any { it.type != SourceSettingDefinition.Type.CALLBACK }

    Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("${configuration.source.name} 设置", style = MaterialTheme.typography.titleSmall)
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
                if (configuration.supportsPasswordLogin || configuration.supportsLoginValidation) {
                    Text("账号", style = MaterialTheme.typography.titleSmall)
                    Text(
                        when {
                            configuration.loggedIn -> "本地已保存登录信息（服务器有效性未确认）"
                            configuration.supportsPasswordLogin -> "未登录"
                            else -> "可检测来源 Cookie 中的登录状态"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (configuration.supportsPasswordLogin && showCredentialFields) {
                        OutlinedTextField(
                            value = account,
                            onValueChange = { account = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("账号 / 邮箱") },
                            singleLine = true,
                            enabled = !state.accountActionInProgress,
                        )
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("密码") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            enabled = !state.accountActionInProgress,
                        )
                        Button(
                            onClick = {
                                onAction(SourcesAction.Login(configuration.source.sourceId, account, password))
                                password = ""
                            },
                            enabled = account.isNotBlank() && password.isNotBlank() && !state.accountActionInProgress,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (state.accountActionInProgress) "处理中…" else "登录并记住账号") }
                    } else if (configuration.supportsPasswordLogin) {
                        Button(
                            onClick = { onAction(SourcesAction.Relogin(configuration.source.sourceId)) },
                            enabled = !state.accountActionInProgress,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (state.accountActionInProgress) "登录中…" else "使用已保存账号重新登录") }
                        TextButton(onClick = { showCredentialFields = true }, enabled = !state.accountActionInProgress) {
                            Text("更换保存的账号信息")
                        }
                    }
                    if (configuration.supportsLoginValidation) {
                        TextButton(
                            onClick = { onAction(SourcesAction.CheckLogin(configuration.source.sourceId)) },
                            enabled = !state.accountActionInProgress,
                        ) {
                            if (configuration.source.sourceId in state.manualLoginCheckSourceIds) {
                                androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.heightIn(max = 18.dp), strokeWidth = 2.dp)
                            } else Text("检测登录状态")
                        }
                    }
                    if (configuration.loggedIn && configuration.supportsLogout) {
                        TextButton(
                            onClick = { onAction(SourcesAction.Logout(configuration.source.sourceId)) },
                            enabled = !state.accountActionInProgress,
                        ) { Text("退出登录") }
                    }
                    state.loginCheckOutcomes[configuration.source.sourceId]?.let { outcome ->
                        val statusText = when (outcome) {
                            dev.veneranative.data.source.SourceLoginCheckOutcome.Valid -> "服务器确认登录有效"
                            dev.veneranative.data.source.SourceLoginCheckOutcome.Invalid -> "服务器确认登录已失效"
                            dev.veneranative.data.source.SourceLoginCheckOutcome.Unsupported -> "此来源不支持在线检测"
                            dev.veneranative.data.source.SourceLoginCheckOutcome.Failed -> "检测失败，无法判断账号状态"
                        }
                        Text(statusText, style = MaterialTheme.typography.bodySmall)
                    }
                    configuration.registerWebsite?.let { url ->
                        TextButton(onClick = { runCatching { uriHandler.openUri(url) } }) { Text("注册账号") }
                    }
                }

                if (configuration.settings.isNotEmpty()) {
                    Text("来源设置", style = MaterialTheme.typography.titleSmall)
                    configuration.settings.forEach { setting ->
                        SourceSettingControl(
                            setting = setting,
                            valueJson = values[setting.key] ?: setting.defaultJson ?: "null",
                            enabled = !state.settingsSaving && !state.accountActionInProgress,
                            onValueChange = { json -> values = values + (setting.key to json) },
                            onCallback = { onAction(SourcesAction.InvokeSettingCallback(configuration.source.sourceId, setting.key)) },
                        )
                    }
                } else if (!configuration.supportsPasswordLogin && !configuration.supportsLoginValidation) {
                    Text("此来源没有声明可调整的设置或账号功能。")
                }
                state.message?.let { message ->
                    val isError = listOf("失败", "无法", "不支持", "失效").any(message::contains)
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                message,
                                modifier = Modifier.weight(1f).padding(vertical = 10.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                            TextButton(onClick = { onAction(SourcesAction.DismissMessage) }) { Text("关闭") }
                        }
                    }
                }
                if (state.settingsSaving || state.accountActionInProgress) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
        }
    }

    if (hasEditableSettings) {
        TextButton(
            onClick = { onAction(SourcesAction.SaveSettings(configuration.source.sourceId, values)) },
            enabled = !state.settingsSaving,
        ) { Text(if (state.settingsSaving) "保存中…" else "保存设置") }
    }
}

@Composable
private fun SourceSettingControl(
    setting: SourceSettingDefinition,
    valueJson: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    onCallback: () -> Unit,
) {
    when (setting.type) {
        SourceSettingDefinition.Type.INPUT -> OutlinedTextField(
            value = runCatching { org.json.JSONTokener(valueJson).nextValue() as? String }.getOrNull().orEmpty(),
            onValueChange = { onValueChange(org.json.JSONObject.quote(it)) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(setting.title) },
            singleLine = true,
            enabled = enabled,
        )
        SourceSettingDefinition.Type.SWITCH -> Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(setting.title, modifier = Modifier.weight(1f))
            Switch(checked = valueJson == "true", onCheckedChange = { onValueChange(it.toString()) }, enabled = enabled)
        }
        SourceSettingDefinition.Type.SELECT -> {
            var expanded by remember(setting.key) { mutableStateOf(false) }
            val selected = setting.options.firstOrNull { org.json.JSONObject.quote(it.value) == valueJson }
            Box {
                TextButton(onClick = { expanded = true }, enabled = enabled) {
                    Text("${setting.title}：${selected?.text ?: setting.options.firstOrNull()?.text ?: "选择"}")
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    setting.options.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.text) },
                            onClick = { onValueChange(org.json.JSONObject.quote(option.value)); expanded = false },
                            enabled = enabled,
                        )
                    }
                }
            }
        }
        SourceSettingDefinition.Type.CALLBACK -> TextButton(onClick = onCallback, enabled = enabled) {
            Text(setting.actionLabel ?: setting.title)
        }
    }
}
