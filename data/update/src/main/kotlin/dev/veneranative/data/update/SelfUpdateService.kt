package dev.veneranative.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import java.io.File
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import kotlin.coroutines.resume

sealed interface SelfUpdateCheck {
    data object UpToDate : SelfUpdateCheck

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
    /** Whether the user already allowed this app to install unknown apps. */
    fun canRequestInstalls(): Boolean

    /** Opens the system page where the user grants that one-time permission. */
    fun openInstallPermissionSettings()

    suspend fun checkForUpdate(installedVersionCode: Long): SelfUpdateCheck

    /** Downloads the APK; [onProgress] is invoked on the caller's dispatcher as bytes arrive. */
    suspend fun download(apk: GitHubReleaseAsset, onProgress: (ApkDownloadProgress) -> Unit): File

    /**
     * Installs the downloaded APK. Suspends through any system confirmation dialog and resolves
     * with the final outcome; on success the system usually kills the app process.
     */
    suspend fun install(apk: File): ApkInstallResult
}

/** GitHub-Releases-backed implementation, assembled by the app layer with its shared HTTP client. */
class SelfUpdateService(
    context: Context,
    httpClient: OkHttpClient,
    private val owner: String,
    private val repository: String,
) : SelfUpdateGateway {
    private val appContext = context.applicationContext
    private val api = GitHubReleaseApi(httpClient)
    private val downloader = ApkDownloader(httpClient)
    private val installer = ApkInstaller()
    private val downloadDirectory: File
        get() = File(appContext.cacheDir, "self-update").apply { mkdirs() }

    override fun canRequestInstalls(): Boolean = appContext.packageManager.canRequestPackageInstalls()

    override fun openInstallPermissionSettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${appContext.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        appContext.startActivity(intent)
    }

    override suspend fun checkForUpdate(installedVersionCode: Long): SelfUpdateCheck {
        val release = api.latestRelease(owner, repository)
        val apk = ReleaseSelection.pickUpdateApk(release.assets) ?: return SelfUpdateCheck.NoInstallableApk
        val versionCode = ReleaseSelection.releaseVersionCode(release.tagName, apk.name)
            ?: return SelfUpdateCheck.NoInstallableApk
        return if (ReleaseSelection.isUpdate(versionCode, installedVersionCode)) {
            SelfUpdateCheck.Available(release, apk, versionCode)
        } else {
            SelfUpdateCheck.UpToDate
        }
    }

    override suspend fun download(apk: GitHubReleaseAsset, onProgress: (ApkDownloadProgress) -> Unit): File {
        val target = File(downloadDirectory, ReleaseSelection.sanitizeAssetFileName(apk.name))
        return downloader.download(apk.downloadUrl, target, onProgress)
    }

    override suspend fun install(apk: File): ApkInstallResult = suspendCancellableCoroutine { continuation ->
        // The session keeps running after cancellation: a closed dialog must not abort an install
        // the user may still confirm; the outcome callback is simply ignored when inactive.
        installer.install(appContext, apk) { result ->
            if (continuation.isActive) continuation.resume(result)
        }
    }
}
