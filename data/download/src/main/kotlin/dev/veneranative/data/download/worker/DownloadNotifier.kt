package dev.veneranative.data.download.worker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo

/**
 * Builds the download notification and the `ForegroundInfo` that keeps the worker alive.
 *
 * A download that runs for minutes cannot be an anonymous background task, so the worker runs in the
 * foreground and the notification is both the legal requirement and the only progress the user gets.
 */
class DownloadNotifier(
    private val context: Context,
) {

    /** Creating an existing channel is a no-op, so this is safe to call before every update. */
    fun ensureChannel() {
        val channel = NotificationChannel(
            DownloadNotificationText.CHANNEL_ID,
            DownloadNotificationText.channelName(),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = DownloadNotificationText.channelDescription()
            setShowBadge(false)
        }
        val system = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        system.createNotificationChannel(channel)
    }

    fun notification(progress: DownloadProgress): Notification {
        ensureChannel()
        return NotificationCompat.Builder(context, DownloadNotificationText.CHANNEL_ID)
            .setContentTitle(DownloadNotificationText.title(progress))
            .setContentText(DownloadNotificationText.content(progress))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(
                progress.totalPages,
                progress.completedPages,
                progress.isUnresolved,
            )
            .addAction(action(android.R.drawable.ic_media_pause, DownloadNotificationText.actionPause(), DownloadNotificationText.ACTION_PAUSE))
            .addAction(action(android.R.drawable.ic_media_play, DownloadNotificationText.actionResume(), DownloadNotificationText.ACTION_RESUME))
            .addAction(action(android.R.drawable.ic_delete, DownloadNotificationText.actionCancel(), DownloadNotificationText.ACTION_CANCEL))
            .build()
    }

    /**
     * The worker's foreground promise.
     *
     * The service type is mandatory from API 34: starting a foreground service without one, or with
     * a type whose permission the manifest does not declare, fails with
     * `MissingForegroundServiceTypeException`. Downloading is a data transfer, hence `dataSync`.
     */
    fun foregroundInfo(progress: DownloadProgress): ForegroundInfo = ForegroundInfo(
        DownloadNotificationText.NOTIFICATION_ID,
        notification(progress),
        foregroundServiceType(),
    )

    private fun action(icon: Int, label: String, action: String): NotificationCompat.Action {
        val intent = Intent(context, DownloadControlReceiver::class.java).setAction(action)
        val flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        val pending = android.app.PendingIntent.getBroadcast(context, action.hashCode(), intent, flags)
        return NotificationCompat.Action(icon, label, pending)
    }

    private fun foregroundServiceType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
}
