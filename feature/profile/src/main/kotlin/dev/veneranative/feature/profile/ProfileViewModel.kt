package dev.veneranative.feature.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.veneranative.data.backup.GitHubAccountService
import dev.veneranative.data.backup.GitHubAccountState
import dev.veneranative.data.backup.GitHubDeviceAuthorization
import dev.veneranative.data.backup.GitHubDevicePollResult
import dev.veneranative.data.backup.GitHubAuthorizationRequiredException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProfileUiState(
    val account: GitHubAccountState = GitHubAccountState(),
    val busy: Boolean = false,
    val authorization: GitHubDeviceAuthorization? = null,
    val error: String? = null,
    val confirmDisconnect: Boolean = false,
)

sealed interface ProfileAction {
    data object Connect : ProfileAction
    data object Refresh : ProfileAction
    data object CancelAuthorization : ProfileAction
    data object RequestDisconnect : ProfileAction
    data object CancelDisconnect : ProfileAction
    data object Disconnect : ProfileAction
}

class ProfileViewModel(
    private val service: GitHubAccountService?,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val workerDispatcher: CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ProfileUiState(account = service?.account?.value ?: GitHubAccountState()))
    val state = mutableState.asStateFlow()
    private var authorizationJob: Job? = null
    private var refreshJob: Job? = null

    init {
        service?.let { accountService ->
            viewModelScope.launch {
                accountService.account.collect { account -> mutableState.update { it.copy(account = account) } }
            }
        }
    }

    fun onAction(action: ProfileAction) {
        when (action) {
            ProfileAction.Connect -> connect()
            ProfileAction.Refresh -> refresh()
            ProfileAction.CancelAuthorization -> {
                authorizationJob?.cancel()
                authorizationJob = null
                mutableState.update { it.copy(busy = false, authorization = null, error = null) }
            }
            ProfileAction.RequestDisconnect -> mutableState.update { it.copy(confirmDisconnect = true) }
            ProfileAction.CancelDisconnect -> mutableState.update { it.copy(confirmDisconnect = false) }
            ProfileAction.Disconnect -> {
                authorizationJob?.cancel()
                refreshJob?.cancel()
                service?.disconnect()
                mutableState.value = ProfileUiState()
            }
        }
    }

    private fun refresh() {
        val accountService = service ?: return
        if (!accountService.account.value.connected || refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            try {
                withContext(workerDispatcher) { accountService.refreshProfile() }
                mutableState.update { it.copy(error = null) }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                mutableState.update { it.copy(error = if (failure is GitHubAuthorizationRequiredException)
                    "GitHub 授权已过期，请重新连接" else "暂时无法更新账号信息，已保留上次的信息") }
            }
        }
    }

    private fun connect() {
        val accountService = service ?: return
        if (authorizationJob?.isActive == true) return
        authorizationJob = viewModelScope.launch {
            mutableState.update { it.copy(busy = true, error = null) }
            try {
                val authorization = withContext(workerDispatcher) { accountService.beginAuthorization() }
                mutableState.update { it.copy(authorization = authorization, busy = false) }
                var interval = authorization.intervalSeconds
                while (nowEpochMillis() < authorization.expiresAtEpochMillis) {
                    delay(interval * 1_000)
                    when (val result = withContext(workerDispatcher) { accountService.pollAuthorization(authorization.deviceCode) }) {
                        GitHubDevicePollResult.Pending -> Unit
                        is GitHubDevicePollResult.WaitLonger -> interval += result.additionalSeconds
                        GitHubDevicePollResult.Expired -> {
                            mutableState.update { it.copy(authorization = null, error = "授权码已过期，请重新连接") }
                            return@launch
                        }
                        GitHubDevicePollResult.Denied -> {
                            mutableState.update { it.copy(authorization = null, error = "授权已取消，可以重新连接 GitHub") }
                            return@launch
                        }
                        is GitHubDevicePollResult.Authorized -> {
                            mutableState.update { it.copy(authorization = null, busy = false) }
                            refresh()
                            return@launch
                        }
                    }
                }
                mutableState.update { it.copy(authorization = null, error = "授权码已过期，请重新连接") }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                mutableState.update { it.copy(busy = false, authorization = null, error = "暂时无法连接 GitHub，请检查网络后重试") }
            }
        }
    }
}
