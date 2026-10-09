package dev.veneranative.feature.backup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.veneranative.core.backup.BackupCategory
import dev.veneranative.data.backup.GitHubBackupGateway
import dev.veneranative.data.backup.GitHubBackupGatewayFactory
import dev.veneranative.data.backup.GitHubDevicePollResult
import dev.veneranative.data.backup.GitHubAuthorizationRequiredException
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class BackupViewModel(private val factory: GitHubBackupGatewayFactory?) : ViewModel() {
    private val mutableState = MutableStateFlow(
        BackupUiState(clientId = factory?.savedClientId().orEmpty(), connected = factory?.hasAuthorization() == true),
    )
    val state: StateFlow<BackupUiState> = mutableState.asStateFlow()
    private var gateway: GitHubBackupGateway? = null

    fun onAction(action: BackupAction) {
        when (action) {
            is BackupAction.ClientIdChanged -> {
                factory?.saveClientId(action.value)
                mutableState.update { it.copy(clientId = action.value, error = null) }
            }
            is BackupAction.PasswordChanged -> mutableState.update { it.copy(password = action.value, error = null) }
            is BackupAction.CategoryToggled -> mutableState.update { state ->
                state.copy(categories = state.categories.toMutableSet().apply {
                    if (!add(action.category)) remove(action.category)
                }, error = null)
            }
            BackupAction.Connect -> connect()
            BackupAction.CreateBackup -> runAction("备份已上传到 GitHub 私有仓库") {
                require(mutableState.value.categories.isNotEmpty()) { "至少选择一种数据" }
                val password = mutableState.value.password.toCharArray()
                try { gateway().createBackup(mutableState.value.categories, password) }
                finally { password.fill('\u0000') }
            }
            BackupAction.Restore -> runAction("恢复完成（采用合并方式，没有清除本机已有数据）") {
                val password = mutableState.value.password.toCharArray()
                try { gateway().restoreBackup(password, mutableState.value.categories) }
                finally { password.fill('\u0000') }
            }
            BackupAction.Disconnect -> {
                if (gateway == null && factory != null && mutableState.value.clientId.isNotBlank()) {
                    gateway = factory.create(mutableState.value.clientId)
                }
                gateway?.disconnect()
                gateway = null
                mutableState.update { it.copy(connected = false, authorization = null, message = "已断开 GitHub 连接") }
            }
        }
    }

    private fun gateway(): GitHubBackupGateway {
        gateway?.let { return it }
        checkNotNull(factory) { "本地备份数据库尚未准备完成，请稍后重试" }
        val clientId = mutableState.value.clientId.trim()
        require(clientId.isNotEmpty()) { "请填写 GitHub App Client ID" }
        return factory.create(clientId).also { service ->
            gateway = service
            if (service.isAuthorized) mutableState.update { it.copy(connected = true) }
        }
    }

    private fun connect() = viewModelScope.launch {
        mutableState.update { it.copy(busy = true, error = null, message = null) }
        try {
            val service = gateway()
            val authorization = service.beginAuthorization()
            mutableState.update { it.copy(authorization = authorization, busy = false, message = "打开 GitHub 授权页面并输入下方授权码") }
            var interval = authorization.intervalSeconds
            while (System.currentTimeMillis() < authorization.expiresAtEpochMillis) {
                delay(interval * 1_000)
                when (val result = service.pollAuthorization(authorization.deviceCode)) {
                    GitHubDevicePollResult.Pending -> Unit
                    is GitHubDevicePollResult.WaitLonger -> interval += result.additionalSeconds
                    GitHubDevicePollResult.Expired -> error("授权码已过期，请重新连接")
                    GitHubDevicePollResult.Denied -> error("你拒绝了 GitHub 授权")
                    is GitHubDevicePollResult.Authorized -> {
                        mutableState.update { it.copy(connected = true, authorization = null, message = "GitHub 已连接；数据只会以加密文件写入私有仓库") }
                        return@launch
                    }
                }
            }
            error("授权等待超时，请重新连接")
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            mutableState.update { it.copy(busy = false, error = failure.message ?: "GitHub 授权失败") }
        }
    }

    private fun runAction(success: String, action: suspend () -> Any) = viewModelScope.launch {
        mutableState.update { it.copy(busy = true, error = null, message = null) }
        try {
            action()
            mutableState.update { it.copy(busy = false, message = success, password = "") }
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            val message = if (failure is GitHubAuthorizationRequiredException) "请先连接 GitHub" else failure.message ?: "操作失败"
            mutableState.update { it.copy(busy = false, error = message) }
        }
    }
}
