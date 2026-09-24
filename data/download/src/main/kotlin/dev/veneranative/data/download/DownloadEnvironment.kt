package dev.veneranative.data.download

import android.content.Context
import dev.veneranative.core.database.VeneraDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Everything the download queue needs from the outside world.
 *
 * All of it is injectable because none of it can be guessed in a test: the clock decides whether a
 * heartbeat is stale, the dispatcher decides whether pages run in parallel, and the file root is the
 * difference between "wrote the page" and "wrote it somewhere the app will never look".
 *
 * [pageSource] is the seam the worker fetches bytes through. It is deliberately the reader's own
 * image pipeline rather than a download-specific HTTP client: a page the reader can show only
 * because it carries the source's cookies and referer must be downloadable for the same reason.
 */
data class DownloadEnvironment(
    /** `filesDir/downloads`: every page path is relative to this root. */
    val filesRoot: File,
    val clock: () -> Long = { System.currentTimeMillis() },
    val io: CoroutineDispatcher = Dispatchers.IO,
    val limits: DownloadLimits = DownloadLimits(),
    /** Where page bytes come from; `ComicImagePipelinePageSource` in production. */
    val pageSource: PageByteSource,
    /** Opened lazily because opening Room is a suspend call and the worker runs as one. */
    val database: suspend () -> VeneraDatabase,
) {

    fun layout(): DownloadFileLayout = DownloadFileLayout(filesRoot)

    /** The concurrency policy is owned by the queue, so the worker never opens coroutines itself. */
    fun queue(): DownloadQueue = DownloadQueue(limits = limits, dispatcher = io)

    fun pageDownloader(): PageDownloader = PageDownloader(layout(), pageSource)

    suspend fun repository(): DownloadRepository =
        DefaultDownloadRepository(database().downloadDao(), layout(), clock, io)

    /**
     * Tells the queue this worker is still alive.
     *
     * Recovery requeues the running pages of any worker that stopped reporting, so a run that goes
     * quiet for longer than `HEARTBEAT_STALE_AFTER_MILLIS` loses its pages to the next scan — the
     * worker beats once per loop, which is far more often than that.
     */
    suspend fun heartbeat(workerId: String) {
        val now = clock()
        withContext(io) { database().downloadDao().heartbeat(workerId, now) }
    }

    companion object {

        @Volatile
        private var installed: DownloadEnvironment? = null

        /**
         * Publishes the process-wide environment.
         *
         * Called from the Application's `onCreate`, never from an Activity: a worker may be the
         * first thing that runs in a process the system started on WorkManager's behalf, and by
         * then the Application has already run while no Activity exists.
         */
        fun install(environment: DownloadEnvironment) {
            installed = environment
        }

        /** For tests only: drops the installed environment so a run cannot see the previous one. */
        fun uninstall() {
            installed = null
        }

        fun isInstalled(): Boolean = installed != null

        /**
         * The environment for [context]'s process.
         *
         * There is no fallback: an environment guessed here would fetch bytes through nothing at
         * all and mark every page unresolvable, which is a silent failure. Failing loudly is the
         * honest outcome, and it can only happen if the Application did not install one.
         */
        fun get(context: Context): DownloadEnvironment = installed
            ?: error(
                "no DownloadEnvironment was installed; the Application must install one in " +
                    "onCreate() before a download worker can run (context=$context)",
            )
    }
}
