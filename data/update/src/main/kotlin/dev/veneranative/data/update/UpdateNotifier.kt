package dev.veneranative.data.update

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo

/**
 * Builds the self-update download notification and the `ForegroundInfo` that keeps the worker alive.
 *
 * The update download runs in the foreground so Android does not kill it once the user leaves the
 * About screen or locks the device. The notification is both the legal requirement for a foreground
 * service and the only progress the user sees while the download continues in the background.
 */
class UpdateNotifier(
    private val context: Context,
) {

    /** Creating an existing channel is a no-op, so this is safe to call before every update. */
    fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            channelName(),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = channelDescription()
            setShowBadge(false)
        }
        val system = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        system.createNotificationChannel(channel)
    }

    fun notification(progress: Float): Notification {
        ensureChannel()
        val percent = (progress.coerceIn(0f, 1f) * 100).toInt()
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title())
            .setContentText(content(percent))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, percent, percent == 0)
            .build()
    }

    /**
     * The worker's foreground promise. The service type is mandatory from API 34; downloading is a
     * data transfer, hence `dataSync`.
     */
    fun foregroundInfo(progress: Float): ForegroundInfo = ForegroundInfo(
        NOTIFICATION_ID,
        notification(progress),
        foregroundServiceType(),
    )

    private fun foregroundServiceType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }

    private fun title(): String = "正在下载新版本"

    private fun content(percent: Int): String = "已下载 $percent%"

    private fun channelName(): String = "应用更新"

    private fun channelDescription(): String = "显示应用内新版本 APK 的下载进度"

    private companion object {
        /** Stable for the life of the app: a channel cannot be renamed after it is created. */
        const val CHANNEL_ID: String = "venera.self_update"

        const val NOTIFICATION_ID: Int = 2002
    }
}
