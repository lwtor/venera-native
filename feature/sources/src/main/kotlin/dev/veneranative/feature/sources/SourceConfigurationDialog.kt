package dev.veneranative.feature.sources

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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

/**
 * One source's settings, rendered as a flat list of uniform [SettingRow]s.
 *
 * There is deliberately no nested scroll container: the caller owns scrolling, so settings flow
 * with the rest of the source list instead of trapping the gesture in a 520dp box. Credentials
 * stay in ordinary in-memory Compose state, never rememberSaveable.
 */
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
    val sourceId = configuration.source.sourceId
    var values by remember(sourceId.value, configuration.settingValues) {
        mutableStateOf(configuration.settingValues.toMap())
    }
    var account by remember(sourceId.value) { mutableStateOf("") }
    var showCredentialFields by remember(sourceId.value, configuration.hasSavedLoginCredentials) {
        mutableStateOf(!configuration.hasSavedLoginCredentials)
    }
    var password by remember(sourceId.value) { mutableStateOf("") }
    var editingKey by remember(sourceId.value) { mutableStateOf<String?>(null) }
    val uriHandler = LocalUriHandler.current
    val busy = state.settingsSaving || state.accountActionInProgress
    val hasEditableSettings = configuration.settings.any { it.type != SourceSettingDefinition.Type.CALLBACK }

    Column(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // ---- Account: both login and logout are ordinary SettingRows ----
        if (configuration.supportsPasswordLogin || configuration.supportsLoginValidation) {
            val statusText = state.loginCheckOutcomes[sourceId]?.let { outcome ->
                when (outcome) {
                    dev.veneranative.data.source.SourceLoginCheckOutcome.Valid -> "服务器确认登录有效"
                    dev.veneranative.data.source.SourceLoginCheckOutcome.Invalid -> "服务器确认登录已失效"
                    dev.veneranative.data.source.SourceLoginCheckOutcome.Unsupported -> "此来源不支持在线检测"
                    dev.veneranative.data.source.SourceLoginCheckOutcome.Failed -> "检测失败，无法判断账号状态"
                }
            } ?: when {
                configuration.loggedIn -> "本地已保存登录信息（未在线确认）"
                configuration.supportsPasswordLogin -> "未登录"
                else -> "可检测 Cookie 中的登录状态"
            }

            if (configuration.supportsLoginValidation) {
                SettingRow(title = "登录状态", subtitle = statusText) {
                    TextButton(
                        onClick = { onAction(SourcesAction.CheckLogin(sourceId)) },
                        enabled = !state.accountActionInProgress,
                    ) {
                        if (sourceId in state.manualLoginCheckSourceIds) {
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.heightIn(max = 18.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Text("检测")
                        }
                    }
                }
            } else {
                SettingRow(title = "登录状态", subtitle = statusText) { }
            }

            if (configuration.supportsPasswordLogin) {
                if (showCredentialFields) {
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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                onAction(SourcesAction.Login(sourceId, account, password))
                                password = ""
                            },
                            enabled = account.isNotBlank() && password.isNotBlank() && !state.accountActionInProgress,
                        ) { Text(if (state.accountActionInProgress) "登录中…" else "登录并记住") }
                        if (configuration.hasSavedLoginCredentials) {
                            TextButton(onClick = { showCredentialFields = false }, enabled = !state.accountActionInProgress) {
                                Text("取消")
                            }
                        }
                    }
                } else {
                    SettingRow(
                        title = "重新登录",
                        subtitle = "登录已过期或需要重新验证时使用",
                    ) {
                        TextButton(
                            onClick = { onAction(SourcesAction.Relogin(sourceId)) },
                            enabled = !state.accountActionInProgress,
                        ) { Text(if (state.accountActionInProgress) "登录中…" else "重新登录") }
                    }
                    SettingRow(title = "更换账号信息") {
                        TextButton(onClick = { showCredentialFields = true }, enabled = !state.accountActionInProgress) {
                            Text("更换")
                        }
                    }
                }
            }

            if (configuration.loggedIn && configuration.supportsLogout) {
                SettingRow(title = "注销") {
                    TextButton(onClick = { onAction(SourcesAction.Logout(sourceId)) }, enabled = !state.accountActionInProgress) {
                        Text("注销")
                    }
                }
            }
            configuration.registerWebsite?.let { url ->
                SettingRow(title = "注册账号") {
                    TextButton(onClick = { runCatching { uriHandler.openUri(url) } }) { Text("打开") }
                }
            }
        }

        // ---- Source settings: one uniform row per declared setting ----
        configuration.settings.forEach { setting ->
            SettingRowControl(
                setting = setting,
                valueJson = values[setting.key] ?: setting.defaultJson ?: "null",
                enabled = !busy,
                editing = editingKey == setting.key,
                onStartEdit = { editingKey = setting.key },
                onStopEdit = { editingKey = null },
                onValueChange = { json -> values = values + (setting.key to json) },
                onCallback = { onAction(SourcesAction.InvokeSettingCallback(sourceId, setting.key)) },
            )
        }
        if (configuration.settings.isEmpty() && !configuration.supportsPasswordLogin && !configuration.supportsLoginValidation) {
            Text(
                "此来源没有声明可调整的设置或账号功能。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (hasEditableSettings) {
            TextButton(
                onClick = { onAction(SourcesAction.SaveSettings(sourceId, values)) },
                enabled = !state.settingsSaving,
                modifier = Modifier.align(Alignment.End),
            ) { Text(if (state.settingsSaving) "保存中…" else "保存设置") }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

/** Fixed "title on the left, control on the right" skeleton shared by every setting type. */
@Composable
private fun SettingRow(
    title: String,
    subtitle: String? = null,
    control: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        control()
    }
}

@Composable
private fun SettingRowControl(
    setting: SourceSettingDefinition,
    valueJson: String,
    enabled: Boolean,
    editing: Boolean,
    onStartEdit: () -> Unit,
    onStopEdit: () -> Unit,
    onValueChange: (String) -> Unit,
    onCallback: () -> Unit,
) {
    when (setting.type) {
        SourceSettingDefinition.Type.INPUT -> {
            val current = runCatching { org.json.JSONTokener(valueJson).nextValue() as? String }.getOrNull().orEmpty()
            if (editing) {
                Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                    OutlinedTextField(
                        value = current,
                        onValueChange = { onValueChange(org.json.JSONObject.quote(it)) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(setting.title) },
                        singleLine = true,
                        enabled = enabled,
                    )
                    TextButton(onClick = onStopEdit) { Text("完成") }
                }
            } else {
                SettingRow(title = setting.title, subtitle = current.ifBlank { "未设置" }) {
                    TextButton(onClick = onStartEdit, enabled = enabled) { Text("编辑") }
                }
            }
        }

        SourceSettingDefinition.Type.SWITCH -> SettingRow(title = setting.title) {
            Switch(checked = valueJson == "true", onCheckedChange = { onValueChange(it.toString()) }, enabled = enabled)
        }

        SourceSettingDefinition.Type.SELECT -> {
            var expanded by remember(setting.key) { mutableStateOf(false) }
            val selected = setting.options.firstOrNull { org.json.JSONObject.quote(it.value) == valueJson }
            androidx.compose.foundation.layout.Box {
                SettingRow(title = setting.title) {
                    TextButton(onClick = { expanded = true }, enabled = enabled) {
                        Text(selected?.text ?: setting.options.firstOrNull()?.text ?: "选择")
                    }
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

        SourceSettingDefinition.Type.CALLBACK -> SettingRow(
            title = setting.title,
            subtitle = setting.actionLabel?.takeIf { it != setting.title },
        ) {
            TextButton(onClick = onCallback, enabled = enabled) {
                Text(setting.actionLabel ?: setting.title)
            }
        }
    }
}
