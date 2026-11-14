package dev.veneranative.data.update

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.File

/**
 * Downloads a release APK in the foreground so the transfer survives the user leaving the About
 * screen or locking the device.
 *
 * The worker owns no update state: it receives the asset URL and target name as input, streams the
 * bytes through [ApkDownloader] (which resumes a `.part` on the next attempt), reports progress via
 * `setProgress`, and returns the finished file's absolute path in its output data.
 */
class UpdateDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val downloadUrl = inputData.getString(INPUT_DOWNLOAD_URL)
        val targetName = inputData.getString(INPUT_TARGET_NAME)
        if (downloadUrl.isNullOrBlank() || targetName.isNullOrBlank()) {
            return Result.failure(workDataOf(OUTPUT_ERROR to "missing download_url or target_name"))
        }

        val notifier = UpdateNotifier(applicationContext)
        val downloader = ApkDownloader(UpdateEnvironment.httpClient(), onDiagnostic = { message ->
            android.util.Log.d("VeneraSelfUpdate", message)
        })
        val directory = File(applicationContext.cacheDir, "self-update").apply { mkdirs() }
        val target = File(directory, ReleaseSelection.sanitizeAssetFileName(targetName))

        setForeground(notifier.foregroundInfo(0f))
        return try {
            downloader.download(downloadUrl, target) { progress ->
                val fraction = progress.fraction ?: 0f
                setProgressAsync(workDataOf(PROGRESS_KEY to fraction))
            }
            Result.success(workDataOf(OUTPUT_FILE_PATH to target.absolutePath))
        } catch (failure: Exception) {
            // Cancellation of the worker is reported by WorkManager; a failed download is a failure
            // result, not a retry — the caller re-enqueues with REPLACE when the user taps again.
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            Result.failure(workDataOf(OUTPUT_ERROR to (failure.message ?: "download failed")))
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        UpdateNotifier(applicationContext).foregroundInfo(0f)

    companion object {
        const val INPUT_DOWNLOAD_URL: String = "download_url"
        const val INPUT_TARGET_NAME: String = "target_name"
        const val OUTPUT_FILE_PATH: String = "file_path"
        const val OUTPUT_ERROR: String = "error"
        const val PROGRESS_KEY: String = "progress"
    }
}
