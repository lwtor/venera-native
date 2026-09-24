package dev.veneranative.data.download.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager

/**
 * The single door the queue is started and stopped through.
 *
 * One unique work, `download`: the queue itself lives in Room, so a second concurrent worker would
 * only fight the first over which pages are whose. `KEEP` means "it is already running, leave it".
 */
object DownloadWorkScheduler {

    const val WORK_NAME: String = "download"

    /**
     * Unmetered network by default: a chapter is tens of megabytes and the user did not ask to spend
     * mobile data on it. Storage-not-low keeps a run from filling the device to the brim.
     */
    fun constraints(): Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.UNMETERED)
        .setRequiresStorageNotLow(true)
        .build()

    /**
     * @param expedited asks the system to start now rather than when constraints happen to be met.
     *   It is a request, not a guarantee: when the expedited quota is spent the work silently
     *   degrades to a normal one, so the queue's `Queued` state — not "is running" — is what the UI
     *   reflects after enqueueing.
     */
    fun request(expedited: Boolean = false): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(constraints())
            .apply {
                if (expedited) setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            }
            .build()

    fun start(context: Context, expedited: Boolean = false) {
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request(expedited))
    }

    fun cancel(context: Context): Operation =
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)
}
