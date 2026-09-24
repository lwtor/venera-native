package dev.veneranative.data.download.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.WorkManager
import dev.veneranative.data.download.DownloadChapterState
import dev.veneranative.data.download.DownloadEnvironment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The three buttons on the download notification.
 *
 * They talk to the repository, not to the worker: pausing and cancelling have to survive the run
 * they interrupted, and the only place that survives is Room. The worker notices because the pages
 * it was about to claim are no longer `Queued`.
 */
class DownloadControlReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in DownloadNotificationText.actions) return
        val appContext = context.applicationContext

        // Cancelling the work first: the worker must stop claiming pages before their rows go away,
        // or a page it already fetched would be written into a directory that is being deleted.
        if (action == DownloadNotificationText.ACTION_CANCEL) {
            WorkManager.getInstance(appContext).cancelUniqueWork(DownloadWorkScheduler.WORK_NAME)
        }

        val pending = goAsync() ?: return
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val repository = DownloadEnvironment.get(appContext).repository()
                when (action) {
                    DownloadNotificationText.ACTION_PAUSE -> repository.observeTasks().first()
                        .filter { it.state in PAUSABLE }
                        .forEach { repository.pause(it.chapter) }

                    DownloadNotificationText.ACTION_RESUME -> {
                        repository.observeTasks().first()
                            .filter { it.state == DownloadChapterState.Paused }
                            .forEach { repository.resume(it.chapter) }
                        DownloadWorkScheduler.start(appContext)
                    }

                    DownloadNotificationText.ACTION_CANCEL -> repository.observeTasks().first()
                        // The notification represents the queue, but finished chapters are durable
                        // offline content. Cancel only unfinished tasks; otherwise one queue action
                        // would silently delete chapters the user had already downloaded.
                        .filter { it.state != DownloadChapterState.Completed }
                        .forEach { repository.cancel(it.chapter) }
                }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {

        /** Everything that still has work left; a finished or already paused chapter has none. */
        val PAUSABLE: Set<DownloadChapterState> = setOf(
            DownloadChapterState.Queued,
            DownloadChapterState.Running,
            DownloadChapterState.Partial,
            DownloadChapterState.Failed,
        )
    }
}
