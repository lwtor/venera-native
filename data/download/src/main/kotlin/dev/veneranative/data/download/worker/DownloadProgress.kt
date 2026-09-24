package dev.veneranative.data.download.worker

/**
 * What the download notification says.
 *
 * Kept free of Android types so the wording can be asserted on the JVM: the notification is the only
 * thing the user sees of a background run, and a wrong count there is a wrong promise.
 */
data class DownloadProgress(
    val chapterTitle: String = "",
    val completedPages: Int = 0,
    val totalPages: Int = 0,
) {
    init {
        require(completedPages >= 0) { "completedPages must not be negative" }
        require(totalPages >= 0) { "totalPages must not be negative" }
    }

    /** True before any chapter's size is known: the run has nothing to report yet. */
    val isUnresolved: Boolean get() = totalPages <= 0
}
