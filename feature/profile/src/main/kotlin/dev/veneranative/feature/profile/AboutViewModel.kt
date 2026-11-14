package dev.veneranative.feature.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.veneranative.data.update.ApkInstallResult
import dev.veneranative.data.update.ApkDownloadProgress
import dev.veneranative.data.update.GitHubRelease
import dev.veneranative.data.update.GitHubReleaseAsset
import dev.veneranative.data.update.GitHubReleaseException
import dev.veneranative.data.update.SelfUpdateCheck
import dev.veneranative.data.update.SelfUpdateGateway
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class AboutUpdatePhase {
    IDLE,
    CHECKING,
    /** A newer release is known and waits for the user to confirm the update. */
    CONFIRM,
    DOWNLOADING,
    INSTALLING,
    /** The system installer was launched; the app process may be killed once the user confirms. */
    INSTALLED,
}

enum class AboutUpdateProblem { NETWORK, NO_RELEASE, NO_INSTALLABLE_APK, DOWNLOAD, INSTALL }

data class AboutUiState(
    val phase: AboutUpdatePhase = AboutUpdatePhase.IDLE,
    val release: GitHubRelease? = null,
    val apk: GitHubReleaseAsset? = null,
    val newVersionCode: Long = 0L,
    val upToDate: Boolean = false,
    val downloadProgress: ApkDownloadProgress? = null,
    val problem: AboutUpdateProblem? = null,
) {
    val newVersionName: String?
        get() = release?.tagName?.removePrefix("v")?.removePrefix("V")
}

sealed interface AboutAction {
    data object CheckForUpdate : AboutAction
    data object StartUpdate : AboutAction
    data object CancelUpdate : AboutAction
    data object DismissInstalled : AboutAction
}

class AboutViewModel(
    private val gateway: SelfUpdateGateway,
    private val installedVersionCode: Long,
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val mutableState = MutableStateFlow(AboutUiState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null

    fun onAction(action: AboutAction) {
        when (action) {
            AboutAction.CheckForUpdate -> checkForUpdate()
            AboutAction.StartUpdate -> startUpdate()
            AboutAction.CancelUpdate -> cancel()
            AboutAction.DismissInstalled -> mutableState.value = AboutUiState()
        }
    }

    private fun checkForUpdate() {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            mutableState.update {
                it.copy(phase = AboutUpdatePhase.CHECKING, problem = null, upToDate = false, release = null, apk = null)
            }
            try {
                val check = withContext(workerDispatcher) { gateway.checkForUpdate(installedVersionCode) }
                when (check) {
                    is SelfUpdateCheck.Available -> mutableState.update {
                        it.copy(
                            phase = AboutUpdatePhase.CONFIRM,
                            release = check.release,
                            apk = check.apk,
                            newVersionCode = check.versionCode,
                            downloadProgress = null,
                        )
                    }
                    SelfUpdateCheck.UpToDate -> mutableState.update { it.copy(phase = AboutUpdatePhase.IDLE, upToDate = true) }
                    SelfUpdateCheck.NoInstallableApk ->
                        mutableState.update { it.copy(phase = AboutUpdatePhase.IDLE, problem = AboutUpdateProblem.NO_INSTALLABLE_APK) }
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                mutableState.update { it.copy(phase = AboutUpdatePhase.IDLE, problem = problemOf(failure)) }
            }
        }
    }

    private fun startUpdate() {
        if (job?.isActive == true) return
        val apk = state.value.apk ?: return
        job = viewModelScope.launch {
            mutableState.update { it.copy(phase = AboutUpdatePhase.DOWNLOADING, downloadProgress = null, problem = null) }
            val file: File = try {
                withContext(workerDispatcher) {
                    gateway.download(apk) { progress ->
                        mutableState.update { current -> current.copy(downloadProgress = progress) }
                    }
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                mutableState.update { it.copy(phase = AboutUpdatePhase.IDLE, problem = AboutUpdateProblem.DOWNLOAD) }
                return@launch
            }
            mutableState.update { it.copy(phase = AboutUpdatePhase.INSTALLING) }
            try {
                when (gateway.install(file)) {
                    ApkInstallResult.Launched -> mutableState.update { it.copy(phase = AboutUpdatePhase.INSTALLED) }
                    is ApkInstallResult.Failure ->
                        mutableState.update { it.copy(phase = AboutUpdatePhase.IDLE, problem = AboutUpdateProblem.INSTALL) }
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                mutableState.update { it.copy(phase = AboutUpdatePhase.IDLE, problem = AboutUpdateProblem.INSTALL) }
            }
        }
    }

    private fun cancel() {
        job?.cancel()
        job = null
        mutableState.value = AboutUiState()
    }

    private fun problemOf(failure: Exception): AboutUpdateProblem =
        if (failure is GitHubReleaseException && failure.statusCode == 404) {
            AboutUpdateProblem.NO_RELEASE
        } else {
            AboutUpdateProblem.NETWORK
        }
}
