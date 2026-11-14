package dev.veneranative.data.update

import android.content.Context
import java.io.File
import okhttp3.OkHttpClient

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
    suspend fun checkForUpdate(installedVersionCode: Long): SelfUpdateCheck

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
    private val downloader = ApkDownloader(httpClient)
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
            SelfUpdateCheck.UpToDate
        }
    }

    override suspend fun download(apk: GitHubReleaseAsset, onProgress: (ApkDownloadProgress) -> Unit): File {
        val target = File(downloadDirectory, ReleaseSelection.sanitizeAssetFileName(apk.name))
        return downloader.download(apk.downloadUrl, target, onProgress)
    }

    override fun install(apk: File): ApkInstallResult = installer.install(appContext, apk)
}
