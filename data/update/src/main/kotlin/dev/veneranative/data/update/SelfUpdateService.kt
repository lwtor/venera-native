package dev.veneranative.data.update

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.io.File
import kotlinx.coroutines.flow.Flow
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
    private val downloader = ApkDownloader(httpClient, onDiagnostic = { message ->
        android.util.Log.d("VeneraSelfUpdate", message)
    })
    private val installer = ApkInstaller(providerAuthority)
    private val downloadDirectory: File
        get() = File(appContext.cacheDir, "self-update").apply { mkdirs() }

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
}
