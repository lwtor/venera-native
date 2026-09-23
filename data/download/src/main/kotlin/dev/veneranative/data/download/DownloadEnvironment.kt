package dev.veneranative.data.download

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.io.File

/**
 * Everything the download queue needs from the outside world.
 *
 * All of it is injectable because none of it can be guessed in a test: the clock decides whether a
 * heartbeat is stale, the dispatcher decides whether pages run in parallel, and the file root is the
 * difference between "wrote the page" and "wrote it somewhere the app will never look".
 */
data class DownloadEnvironment(
    /** `filesDir/downloads`: every page path is relative to this root. */
    val filesRoot: File,
    val clock: () -> Long = { System.currentTimeMillis() },
    val io: CoroutineDispatcher = Dispatchers.IO,
    val limits: DownloadLimits = DownloadLimits(),
) {

    fun layout(): DownloadFileLayout = DownloadFileLayout(filesRoot)
}
