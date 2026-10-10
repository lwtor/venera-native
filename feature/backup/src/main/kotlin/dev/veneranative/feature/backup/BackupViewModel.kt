package dev.veneranative.feature.backup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.veneranative.data.backup.GitHubBackupService
import dev.veneranative.data.backup.GitHubAuthorizationRequiredException
import dev.veneranative.data.backup.GitHubApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BackupViewModel(
    private val service: GitHubBackupService?,
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val mutableState = MutableStateFlow(BackupUiState(connected = service?.account?.value?.connected == true))
    val state = mutableState.asStateFlow()

    init {
        service?.let { gateway ->
            viewModelScope.launch {
                gateway.account.collect { account -> mutableState.update { it.copy(connected = account.connected) } }
            }
        }
    }

    fun clearPassword() { mutableState.update { it.copy(password = "", confirmRestore = false) } }

    fun onAction(action: BackupAction) {
        if (mutableState.value.busy) return
        when (action) {
            is BackupAction.PasswordChanged -> mutableState.update { it.copy(password = action.value, error = null, message = null) }
            is BackupAction.CategoryToggled -> mutableState.update { state ->
                state.copy(categories = state.categories.toMutableSet().apply {
                    if (!add(action.category)) remove(action.category)
                }, error = null, message = null)
            }
            BackupAction.CreateBackup -> runAction(restoring = false)
            BackupAction.RequestRestore -> if (mutableState.value.canSubmit) mutableState.update { it.copy(confirmRestore = true) }
            BackupAction.CancelRestore -> mutableState.update { it.copy(confirmRestore = false) }
            BackupAction.Restore -> if (mutableState.value.confirmRestore) runAction(restoring = true)
        }
    }

    private fun runAction(restoring: Boolean) {
        val service = service ?: return
        val request = mutableState.value
        if (!request.canSubmit) return
        val password = request.password.toCharArray()
        mutableState.update { it.copy(busy = true, restoring = restoring, confirmRestore = false, error = null, message = null) }
        viewModelScope.launch {
            try {
                withContext(workerDispatcher) {
                    if (restoring) service.restoreBackup(password, request.categories)
                    else service.createBackup(request.categories, password)
                }
                mutableState.update { it.copy(busy = false, password = "", message =
                    if (restoring) "恢复完成，所选数据已合并到本机" else "备份成功，已安全保存到 GitHub") }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                val message = when {
                    failure is GitHubAuthorizationRequiredException -> "GitHub 授权已过期，请返回我的重新连接"
                    failure is GitHubApiException && failure.statusCode == 404 -> "还没有可恢复的备份，请先完成一次备份"
                    failure is GitHubApiException && failure.statusCode in 401..403 -> "GitHub 授权不可用，请返回我的重新连接并检查仓库访问权限"
                    restoring && failure is IllegalArgumentException -> "无法恢复，请检查密码、备份文件及所选内容"
                    else -> "操作未完成，请检查网络后重试"
                }
                mutableState.update { it.copy(busy = false, error = message) }
            } finally { password.fill(0.toChar()) }
        }
    }
}
