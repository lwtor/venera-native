package dev.veneranative.feature.sources

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.veneranative.core.model.InstalledSourceConfiguration
import dev.veneranative.core.model.SourceSettingDefinition
import dev.veneranative.data.source.SourceLoginCheckOutcome

/**
 * One source's settings, as grouped rows inside the card that was opened.
 *
 * Every control applies itself: there is no save button and no edit/confirm dance, because the user
 * asked for settings that behave like switches rather than a form. The ViewModel coalesces the
 * writes, so a control can report every keystroke without the screen having to think about it.
 *
 * Groups are drawn as their own surfaces: account, declared settings, maintenance. A flat list of
 * identical "title — text button" rows is what made this screen unreadable before, so the grouping
 * is structural, not decorative.
 */
@Composable
internal fun SourceSettingsBody(
    configuration: InstalledSourceConfiguration?,
    configurationLoading: Boolean,
    state: SourcesUiState,
    busy: Boolean,
    onAction: (SourcesAction) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        when {
            configuration != null -> {
                if (configuration.hasAccountSurface) {
                    AccountSection(
                        configuration = configuration,
                        state = state,
                        onAction = onAction,
                    )
                }
                if (configuration.settings.isNotEmpty()) {
                    DeclaredSettingsSection(
                        configuration = configuration,
                        state = state,
                        busy = busy,
                        onAction = onAction,
                    )
                }
                if (!configuration.hasAccountSurface && configuration.settings.isEmpty()) {
                    Text(
                        "此漫画源没有提供可调整的设置。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            configurationLoading -> Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Text(
                        "正在读取设置…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            else -> Text(
                "无法读取该漫画源的设置。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        state.settingsMessage?.let { message ->
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** True when the source declared anything account-shaped; otherwise the section is noise. */
private val InstalledSourceConfiguration.hasAccountSurface: Boolean
    get() = supportsPasswordLogin || supportsLoginValidation || supportsLogout || registerWebsite != null

@Composable
private fun AccountSection(
    configuration: InstalledSourceConfiguration,
    state: SourcesUiState,
    onAction: (SourcesAction) -> Unit,
) {
    val sourceId = configuration.source.sourceId
    var account by remember(sourceId.value) { mutableStateOf("") }
    var password by remember(sourceId.value) { mutableStateOf("") }
    var editingCredentials by remember(sourceId.value, configuration.hasSavedLoginCredentials) {
        mutableStateOf(configuration.supportsPasswordLogin && !configuration.hasSavedLoginCredentials)
    }
    val busy = state.accountActionInProgress
    val status = configuration.loginStatus(state)
    val uriHandler = LocalUriHandler.current

    SettingsGroup(title = "账号") {
        SettingRow(title = "登录状态") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusChip(text = status.text, container = status.container, content = status.content)
                if (configuration.supportsLoginValidation) {
                    IconButton(
                        onClick = { onAction(SourcesAction.CheckLogin(sourceId)) },
                        enabled = !state.accountActionInProgress,
                        modifier = Modifier.size(36.dp),
                    ) {
                        if (sourceId in state.manualLoginCheckSourceIds) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = "重新检测登录状态",
                                modifier = Modifier.size(17.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }

        if (editingCredentials) {
            RowDivider()
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = account,
                    onValueChange = { account = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("账号 / 邮箱") },
                    singleLine = true,
                    enabled = !busy,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    enabled = !busy,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (configuration.hasSavedLoginCredentials) {
                        TextButton(onClick = { editingCredentials = false }, enabled = !busy) { Text("取消") }
                    }
                    Button(
                        onClick = {
                            onAction(SourcesAction.Login(sourceId, account, password))
                            password = ""
                        },
                        enabled = account.isNotBlank() && password.isNotBlank() && !busy,
                    ) { Text(if (busy) "登录中…" else "登录") }
                }
            }
        } else if (configuration.supportsPasswordLogin) {
            RowDivider()
            SettingRow(
                title = "重新登录",
                subtitle = "登录过期或需要重新验证时使用",
            ) {
                TextButton(onClick = { onAction(SourcesAction.Relogin(sourceId)) }, enabled = !busy) {
                    Text(if (busy) "登录中…" else "重新登录")
                }
            }
            RowDivider()
            SettingRow(title = "账号信息", subtitle = "更换账号或密码") {
                TextButton(onClick = { editingCredentials = true }, enabled = !busy) { Text("更换") }
            }
        }

        if (configuration.loggedIn && configuration.supportsLogout) {
            RowDivider()
            SettingRow(
                title = "退出登录",
                titleColor = MaterialTheme.colorScheme.error,
                subtitle = "清除此来源保存的登录信息",
            ) {
                TextButton(onClick = { onAction(SourcesAction.Logout(sourceId)) }, enabled = !busy) { Text("退出") }
            }
        }

        configuration.registerWebsite?.let { url ->
            RowDivider()
            SettingRow(title = "注册账号", subtitle = "在来源站点上注册") {
                TextButton(onClick = { runCatching { uriHandler.openUri(url) } }) { Text("打开") }
            }
        }
    }
}

@Composable
private fun DeclaredSettingsSection(
    configuration: InstalledSourceConfiguration,
    state: SourcesUiState,
    busy: Boolean,
    onAction: (SourcesAction) -> Unit,
) {
    val sourceId = configuration.source.sourceId
    SettingsGroup(
        title = "设置",
        hint = if (state.settingsSaving) "保存中…" else null,
    ) {
        configuration.settings.forEachIndexed { index, setting ->
            if (index > 0) RowDivider()
            SettingControl(
                setting = setting,
                valueJson = configuration.settingValues[setting.key] ?: setting.defaultJson ?: "null",
                enabled = !busy,
                onValueChange = { json -> onAction(SourcesAction.SetSettingValue(sourceId, setting.key, json)) },
                onCallback = { onAction(SourcesAction.InvokeSettingCallback(sourceId, setting.key)) },
            )
        }
    }
}

/** A labelled group of rows on its own surface, so the eye can count groups instead of rows. */
@Composable
private fun SettingsGroup(
    title: String,
    hint: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (hint != null) {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(content = content)
        }
    }
}

/** Title left, control right. The whole row is a target when [onClick] is given. */
@Composable
private fun SettingRow(
    title: String,
    subtitle: String? = null,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .defaultMinSize(minHeight = 52.dp)
            .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = titleColor)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing()
    }
}

@Composable
private fun RowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 14.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
    )
}

@Composable
private fun StatusChip(text: String, container: Color, content: Color) {
    Surface(shape = RoundedCornerShape(6.dp), color = container) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = content,
        )
    }
}

private data class LoginStatus(val text: String, val container: Color, val content: Color)

@Composable
private fun InstalledSourceConfiguration.loginStatus(state: SourcesUiState): LoginStatus {
    val scheme = MaterialTheme.colorScheme
    val positive = LoginStatus("已登录", scheme.secondaryContainer, scheme.onSecondaryContainer)
    val neutral = LoginStatus("未登录", scheme.surfaceContainerHighest, scheme.onSurfaceVariant)
    val broken = LoginStatus("登录已失效", scheme.errorContainer, scheme.onErrorContainer)
    return when (state.loginCheckOutcomes[source.sourceId]) {
        SourceLoginCheckOutcome.Valid -> positive
        SourceLoginCheckOutcome.Invalid -> broken
        SourceLoginCheckOutcome.Failed -> LoginStatus("检测失败", scheme.errorContainer, scheme.onErrorContainer)
        SourceLoginCheckOutcome.Unsupported -> LoginStatus("无法检测", scheme.surfaceContainerHighest, scheme.onSurfaceVariant)
        null -> when {
            loggedIn -> LoginStatus("已登录", scheme.secondaryContainer, scheme.onSecondaryContainer)
            supportsPasswordLogin -> neutral
            else -> LoginStatus("待检测", scheme.surfaceContainerHighest, scheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SettingControl(
    setting: SourceSettingDefinition,
    valueJson: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    onCallback: () -> Unit,
) {
    when (setting.type) {
        SourceSettingDefinition.Type.INPUT -> SettingField(
            setting = setting,
            valueJson = valueJson,
            enabled = enabled,
            onValueChange = onValueChange,
        )

        SourceSettingDefinition.Type.SWITCH -> SettingRow(title = setting.title) {
            Switch(
                checked = valueJson == "true",
                onCheckedChange = { onValueChange(it.toString()) },
                enabled = enabled,
                modifier = Modifier.padding(end = 8.dp),
            )
        }

        SourceSettingDefinition.Type.SELECT -> {
            var expanded by remember(setting.key) { mutableStateOf(false) }
            val selected = setting.options.firstOrNull { org.json.JSONObject.quote(it.value) == valueJson }
                ?: setting.options.firstOrNull()
            Box {
                SettingRow(
                    title = setting.title,
                    onClick = if (enabled) ({ expanded = true }) else null,
                ) {
                    Row(
                        modifier = Modifier.padding(end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = selected?.text ?: "选择",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Icon(
                            Icons.Filled.KeyboardArrowDown,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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
            onClick = if (enabled) onCallback else null,
        ) {
            Text(
                text = setting.actionLabel ?: "执行",
                modifier = Modifier.padding(end = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Text settings are edited in place.
 *
 * The previous "编辑 → 输入 → 完成 → 保存" sequence made a one-line value cost four taps; the field
 * now reports every keystroke and the ViewModel folds them into one write.
 */
@Composable
private fun SettingField(
    setting: SourceSettingDefinition,
    valueJson: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
) {
    val stored = remember(valueJson) { parseJsonString(valueJson) }
    var text by remember(setting.key) { mutableStateOf(stored) }
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = setting.title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                onValueChange(org.json.JSONObject.quote(it))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("未设置") },
            singleLine = true,
            enabled = enabled,
            textStyle = MaterialTheme.typography.bodyMedium,
        )
    }
}

private fun parseJsonString(valueJson: String): String =
    runCatching { org.json.JSONTokener(valueJson).nextValue() as? String }.getOrNull().orEmpty()
