package dev.veneranative.data.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException

sealed interface ApkInstallResult {
    /** The system package installer was launched with the APK. */
    data object Launched : ApkInstallResult

    data class Failure(val message: String?) : ApkInstallResult
}

/**
 * Hands the downloaded APK to the system package installer via a FileProvider URI
 * (`ACTION_VIEW` with `application/vnd.android.package-archive`). The system installer shows the
 * confirmation dialog itself, so no `REQUEST_INSTALL_PACKAGES` grant is needed — the user approves
 * each install individually, mirroring how WeChat installs APKs. No install result is reported
 * back; the caller only knows whether the installer was launched successfully.
 */
class ApkInstaller(private val authority: String) {

    fun install(context: Context, apk: File): ApkInstallResult {
        if (!apk.isFile) return ApkInstallResult.Failure("the downloaded file is missing")
        val uri = runCatching {
            FileProvider.getUriForFile(context, authority, apk)
        }.getOrElse {
            return ApkInstallResult.Failure("unable to expose the APK: ${it.message}")
        }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME_TYPE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return try {
            context.startActivity(intent)
            ApkInstallResult.Launched
        } catch (error: Exception) {
            when (error) {
                is android.content.ActivityNotFoundException ->
                    ApkInstallResult.Failure("no installer is available on this device")
                else -> ApkInstallResult.Failure(error.message)
            }
        }
    }

    companion object {
        private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    }
}
