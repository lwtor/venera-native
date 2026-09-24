package dev.veneranative.data.download.worker

/**
 * Every string and action the download notification uses, as pure functions.
 *
 * No `Context`, no `Notification`: the wording is the part that can be wrong in a way a user notices
 * ("12 / 9"), and putting it here means it is asserted on the JVM instead of only on a device.
 */
object DownloadNotificationText {

    /** Stable for the life of the app: a channel cannot be renamed after it is created. */
    const val CHANNEL_ID: String = "venera.downloads"

    const val NOTIFICATION_ID: Int = 2001

    const val ACTION_PAUSE: String = "dev.veneranative.download.action.PAUSE"
    const val ACTION_RESUME: String = "dev.veneranative.download.action.RESUME"
    const val ACTION_CANCEL: String = "dev.veneranative.download.action.CANCEL"

    /** Every action this notification can send; the receiver refuses anything else. */
    val actions: Set<String> = setOf(ACTION_PAUSE, ACTION_RESUME, ACTION_CANCEL)

    fun channelName(): String = "Downloads"

    fun channelDescription(): String = "Progress of chapters being saved for offline reading"

    /** The headline: which chapter is being worked on, or that nothing has started yet. */
    fun title(progress: DownloadProgress): String =
        if (progress.chapterTitle.isBlank()) IDLE_TITLE else "Downloading ${progress.chapterTitle}"

    /** "3 / 20", or a plain sentence while the chapter's size is still unknown. */
    fun content(progress: DownloadProgress): String =
        if (progress.isUnresolved) UNRESOLVED_CONTENT else "${progress.completedPages} / ${progress.totalPages}"

    fun actionPause(): String = "Pause"

    fun actionResume(): String = "Resume"

    fun actionCancel(): String = "Cancel"

    private const val IDLE_TITLE: String = "Preparing download"
    private const val UNRESOLVED_CONTENT: String = "Starting"
}
