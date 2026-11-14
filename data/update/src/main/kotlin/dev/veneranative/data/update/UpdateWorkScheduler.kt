package dev.veneranative.data.update

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.WorkManager
import androidx.work.workDataOf

/**
 * The single door the self-update download is started and stopped through.
 *
 * One unique work, `self_update`: an update download is a one-off task, and `REPLACE` means tapping
 * "update" again restarts the download from scratch rather than queueing a second worker behind the
 * first. The resumable `.part` handling in [ApkDownloader] makes a restart cheap anyway.
 */
object UpdateWorkScheduler {

    const val WORK_NAME: String = "self_update"

    fun constraints(): Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun start(context: Context, downloadUrl: String, targetName: String) {
        val request = OneTimeWorkRequestBuilder<UpdateDownloadWorker>()
            .setInputData(
                workDataOf(
                    UpdateDownloadWorker.INPUT_DOWNLOAD_URL to downloadUrl,
                    UpdateDownloadWorker.INPUT_TARGET_NAME to targetName,
                ),
            )
            .setConstraints(constraints())
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancel(context: Context): Operation =
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)
}
