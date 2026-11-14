package dev.veneranative.data.update

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient

sealed interface SelfUpdateCheck {
    /** The installed version is already the latest, but the release it found is still exposed so a
     *  caller can force a re-download of the same APK (debug/testing aid). */
    data class UpToDate(
        val release: GitHubRelease,
        val apk: GitHubReleaseAsset,
    ) : SelfUpdateCheck

    /** A release exists but carries no APK this updater can install (missing or unrecognised). */
    data object NoInstallableApk : SelfUpdateCheck

    data class Available(
        val release: GitHubRelease,
        val apk: GitHubReleaseAsset,
        val versionCode: Long,
    ) : SelfUpdateCheck
}

/**
 * The process-wide self-update status driven by the startup prefetch. The app layer collects it
 * to light the badge on the profile screen; `Downloaded` means an APK is ready to install.
 */
sealed interface SelfUpdateStatus {
    data object Idle : SelfUpdateStatus

    /** A newer release is known; its APK is either not yet downloaded or failed to prefetch. */
    data class UpdateAvailable(val versionCode: Long) : SelfUpdateStatus

    /** The APK for [versionCode] has been downloaded and is ready to hand to the installer. */
    data class Downloaded(val versionCode: Long, val apkFile: File) : SelfUpdateStatus
}

/**
 * The surface the About screen drives. Kept as an interface so the update flow logic in
 * `:feature:profile` stays JVM-testable without Android framework types.
 */
interface SelfUpdateGateway {
    suspend fun checkForUpdate(installedVersionCode: Long): SelfUpdateCheck

    /** Downloads the APK in the foreground via a WorkManager worker and awaits its completion. */
    suspend fun downloadInForeground(apk: GitHubReleaseAsset): File

    /** Emits the foreground download's progress as a fraction in `0f..1f`; null while unresolved. */
    fun observeDownloadProgress(): Flow<Float?>

    /** Cancels the in-flight foreground download, if any. */
    fun cancelDownload()

    /** Downloads the APK; [onProgress] is invoked on the caller's dispatcher as bytes arrive. */
    suspend fun download(apk: GitHubReleaseAsset, onProgress: (ApkDownloadProgress) -> Unit): File

    /**
     * Hands the downloaded APK to the system package installer. Returns immediately once the
     * installer is launched; the user confirms the install in the system dialog. No install
     * outcome is reported back.
     */
    fun install(apk: File): ApkInstallResult

    /** Returns the already-downloaded APK for [versionCode], or null when none is cached. */
    fun cachedApk(versionCode: Long): File?
}

/** GitHub-Releases-backed implementation, assembled by the app layer with its shared HTTP client. */
class SelfUpdateService(
    context: Context,
    httpClient: OkHttpClient,
    private val owner: String,
    private val repository: String,
    private val providerAuthority: String,
) : SelfUpdateGateway {
    private val appContext = context.applicationContext
    private val api = GitHubReleaseApi(httpClient)
    private val downloader = ApkDownloader(httpClient)
    private val installer = ApkInstaller(providerAuthority)
    private val stateStore = UpdateStateStore(appContext)
    private val downloadDirectory: File
        get() = File(appContext.cacheDir, "self-update").apply { mkdirs() }

    private val mutableUpdateStatus = MutableStateFlow<SelfUpdateStatus>(SelfUpdateStatus.Idle)
    val updateStatus: StateFlow<SelfUpdateStatus> = mutableUpdateStatus.asStateFlow()

    override suspend fun checkForUpdate(installedVersionCode: Long): SelfUpdateCheck {
        val release = api.latestRelease(owner, repository)
        val apk = ReleaseSelection.pickUpdateApk(release.assets) ?: return SelfUpdateCheck.NoInstallableApk
        val versionCode = ReleaseSelection.releaseVersionCode(release.tagName, apk.name)
            ?: return SelfUpdateCheck.NoInstallableApk
        return if (ReleaseSelection.isUpdate(versionCode, installedVersionCode)) {
            SelfUpdateCheck.Available(release, apk, versionCode)
        } else {
            SelfUpdateCheck.UpToDate(release, apk)
        }
    }

    override suspend fun downloadInForeground(apk: GitHubReleaseAsset): File {
        val targetName = ReleaseSelection.sanitizeAssetFileName(apk.name)
        UpdateWorkScheduler.start(appContext, apk.downloadUrl, targetName)

        // Wait until the worker either succeeds (reading the output path) or stops in a terminal
        // failure state, then surface it to the caller as an exception so the About screen can map
        // it to a download problem instead of silently installing a missing file.
        val manager = WorkManager.getInstance(appContext)
        val workInfo = manager.getWorkInfosForUniqueWorkFlow(UpdateWorkScheduler.WORK_NAME)
            .first { infos -> infos.isNotEmpty() && infos.all { it.state.isFinished } }
        val finished = workInfo.last()
        if (finished.state == WorkInfo.State.SUCCEEDED) {
            val path = finished.outputData.getString(UpdateDownloadWorker.OUTPUT_FILE_PATH)
            if (!path.isNullOrBlank()) return File(path)
        }
        val reason = finished.outputData.getString(UpdateDownloadWorker.OUTPUT_ERROR)
            ?: "update download did not complete (${finished.state})"
        throw ApkDownloadException(reason)
    }

    override fun observeDownloadProgress(): Flow<Float?> =
        WorkManager.getInstance(appContext)
            .getWorkInfosForUniqueWorkFlow(UpdateWorkScheduler.WORK_NAME)
            .map { infos ->
                infos.lastOrNull()
                    ?.progress
                    ?.getFloat(UpdateDownloadWorker.PROGRESS_KEY, -1f)
                    ?.takeIf { it >= 0f }
            }
            .distinctUntilChanged()

    override fun cancelDownload() {
        UpdateWorkScheduler.cancel(appContext)
    }

    override suspend fun download(apk: GitHubReleaseAsset, onProgress: (ApkDownloadProgress) -> Unit): File {
        val target = File(downloadDirectory, ReleaseSelection.sanitizeAssetFileName(apk.name))
        return downloader.download(apk.downloadUrl, target, onProgress)
    }

    override fun install(apk: File): ApkInstallResult = installer.install(appContext, apk)

    override fun cachedApk(versionCode: Long): File? = stateStore.cachedApkForVersion(versionCode)

    /**
     * Startup update check plus silent prefetch. Runs on the graph's IO scope; any failure is
     * swallowed so a transient network problem never crashes or spams the user. A known update is
     * surfaced through [updateStatus] so the profile badge lights even when the prefetch fails.
     */
    suspend fun checkAndPrefetch(installedVersionCode: Long) {
        val check = try {
            checkForUpdate(installedVersionCode)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            return
        }
        val available = when (check) {
            is SelfUpdateCheck.Available -> check
            else -> return
        }

        mutableUpdateStatus.value = SelfUpdateStatus.UpdateAvailable(available.versionCode)

        stateStore.cachedApkForVersion(available.versionCode)?.let { cached ->
            mutableUpdateStatus.value = SelfUpdateStatus.Downloaded(available.versionCode, cached)
            return
        }

        val target = File(downloadDirectory, ReleaseSelection.sanitizeAssetFileName(available.apk.name))
        try {
            downloader.download(available.apk.downloadUrl, target)
            stateStore.saveDownloadedApk(available.versionCode, target.absolutePath)
            mutableUpdateStatus.value = SelfUpdateStatus.Downloaded(available.versionCode, target)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            // Keep the badge lit; the user can still tap through and retry the download manually.
            mutableUpdateStatus.value = SelfUpdateStatus.UpdateAvailable(available.versionCode)
        }
    }
}
