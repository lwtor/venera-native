package dev.veneranative.data.download

import dev.veneranative.core.model.ChapterRef
import dev.veneranative.core.model.SourcePage
import kotlinx.coroutines.flow.Flow

/**
 * One page of a download.
 *
 * The names are stored in the database exactly as written here, because `DownloadDao` filters on
 * them: renaming one changes what the queue's SQL matches, which is why a test asserts the names.
 */
enum class DownloadPageState {
    Queued,
    Running,

    /** Written to disk and validated as an image. */
    Succeeded,
    Failed,
    Paused,
    Canceled,
}

/** What a chapter's download looks like, derived from its pages rather than stored separately. */
enum class DownloadChapterState {
    Queued,
    Running,
    Paused,
    Completed,

    /** Some pages could not be fetched; the chapter is readable only partially. */
    Partial,
    Failed,
    Canceled,
}

/** One chapter the user asked to download, with the progress its pages add up to. */
data class DownloadTask(
    val chapter: ChapterRef,
    val title: String,
    val comicTitle: String?,
    val pageCount: Int,
    val completedPages: Int,
    val state: DownloadChapterState,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

/** One page of a download. [relativePath] keeps device layout out of the database and the UI. */
data class DownloadPage(
    val chapter: ChapterRef,
    val index: Int,
    val imageRef: String,
    val state: DownloadPageState,
    val relativePath: String? = null,
    val bytes: Long = 0L,
    val attempts: Int = 0,
    val lastError: DownloadError? = null,
)

/**
 * Why a page could not be downloaded.
 *
 * A domain error, not an exception: the reason a page is missing is something the UI has to say in
 * its own words, and a lower layer's diagnostic text is written for whoever debugs the source.
 */
sealed interface DownloadError {

    data object Network : DownloadError

    data object StorageFull : DownloadError

    /** The bytes arrived but are not a readable image: a hotlink guard answered with HTML. */
    data class Corrupt(val reason: String) : DownloadError

    /** The image pipeline could not produce the bytes at all. */
    data object NotResolvable : DownloadError
}

/**
 * The download queue: what is being fetched, and what the user can ask for.
 *
 * Room is the only place the queue lives. A page that was fetched before the process died is still
 * fetched when it restarts, because the rows — not a worker's memory — are what say so.
 */
interface DownloadRepository {

    fun observeTasks(): Flow<List<DownloadTask>>

    fun observeTask(chapter: ChapterRef): Flow<DownloadTask?>

    /** Adds a chapter's pages; adding the same chapter again never duplicates a page row. */
    suspend fun enqueue(
        chapter: ChapterRef,
        title: String,
        pages: List<SourcePage>,
        comicTitle: String? = null,
    )

    suspend fun pause(chapter: ChapterRef)

    suspend fun resume(chapter: ChapterRef)

    /** Removes the chapter and everything downloaded for it; nothing is left in its directory. */
    suspend fun cancel(chapter: ChapterRef)

    suspend fun retryFailed(chapter: ChapterRef)

    /** Run on process start and before a worker starts: see `DownloadRecovery`. */
    suspend fun recover(workerId: String): RecoveryReport

    /** True when every page is on disk and still intact, which is the only promise offline reading needs. */
    suspend fun isCompleteOffline(chapter: ChapterRef): Boolean

    suspend fun pagesOf(chapter: ChapterRef): List<DownloadPage>

    /** Pages waiting to be fetched, oldest chapter first and lowest page index first. */
    suspend fun queuedPages(limit: Int): List<DownloadPage>

    /** False when the page is not in a state that may start, e.g. it was cancelled. */
    suspend fun markRunning(chapter: ChapterRef, index: Int): Boolean

    /** Stops an in-flight page without counting an interruption as a failed network attempt. */
    suspend fun markPaused(chapter: ChapterRef, index: Int): Boolean

    suspend fun markSucceeded(chapter: ChapterRef, index: Int, relativePath: String, bytes: Long): Boolean

    suspend fun markFailed(chapter: ChapterRef, index: Int, error: DownloadError): Boolean
}
