package dev.veneranative.data.download

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import java.util.concurrent.ConcurrentHashMap

/** How many pages may be in flight at once, overall and per source. */
data class DownloadLimits(
    val global: Int = MAX_GLOBAL_PAGES,
    val perSource: Int = MAX_PAGES_PER_SOURCE,
) {
    init {
        require(global > 0) { "global must be positive" }
        require(perSource > 0) { "perSource must be positive" }
    }
}

/**
 * Four pages at a time: enough to keep a fast connection busy, few enough that a slow chapter does
 * not fill the connection pool a reader is waiting on.
 */
const val MAX_GLOBAL_PAGES: Int = 4

/**
 * Two per source. Sources rate-limit and some ban outright, and a single chapter is one source, so
 * without this the global limit would be spent entirely on whichever chapter was enqueued first.
 */
const val MAX_PAGES_PER_SOURCE: Int = 2

/** One page handed to the queue. */
data class QueuePage(
    val taskId: String,
    val pageIndex: Int,
    val sourceId: String,
    val imageRef: String,
)

/** What one page run produced. */
data class PageRunResult(
    val page: QueuePage,
    val error: DownloadError?,
)

/**
 * Runs pages concurrently within [DownloadLimits].
 *
 * The limits are enforced here rather than hoped for: two sources' pages can be in flight at once,
 * but no more than two of any one source, so a chapter that stalls cannot take the whole queue with
 * it. Pages start in the order given, which is the order the reader will want them in.
 */
class DownloadQueue(
    private val limits: DownloadLimits = DownloadLimits(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val global = Semaphore(limits.global)
    private val perSource = ConcurrentHashMap<String, Semaphore>()

    /**
     * Fetches [pages], calling [block] once each; null from [block] means success.
     *
     * Results come back in the order the pages were given, and a page that threw is a failed page
     * rather than a cancelled batch: one bad page must not cost the chapter its other pages.
     */
    suspend fun run(
        pages: List<QueuePage>,
        block: suspend (QueuePage) -> DownloadError?,
    ): List<PageRunResult> = coroutineScope {
        pages.map { page ->
            async(dispatcher) {
                val sourceSemaphore = perSource.computeIfAbsent(page.sourceId) {
                    Semaphore(limits.perSource)
                }
                withPermit(global) {
                    withPermit(sourceSemaphore) {
                        val error = runCatching { block(page) }
                            .getOrElse { DownloadError.Corrupt("the page could not be written") }
                        PageRunResult(page = page, error = error)
                    }
                }
            }
        }.awaitAll()
    }

    private suspend fun <T> withPermit(semaphore: Semaphore, block: suspend () -> T): T {
        semaphore.acquire()
        return try {
            block()
        } finally {
            semaphore.release()
        }
    }
}
