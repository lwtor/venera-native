package dev.veneranative.data.history

import dev.veneranative.core.model.ComicKey
import dev.veneranative.core.model.RemoteChapterId
import kotlinx.coroutines.flow.Flow

/** Where the reader should resume, or nothing if this comic was never opened. */
data class ReadingProgress(
    val comicKey: ComicKey,
    val chapterId: RemoteChapterId,
    val pageIndex: Int,
    val updatedAtEpochMillis: Long,
)

/** One row of "continue reading": enough to render a card without asking the source again. */
data class ReadingHistoryEntry(
    val comicKey: ComicKey,
    val comicTitle: String,
    val chapterId: RemoteChapterId,
    val chapterTitle: String,
    val coverUrl: String?,
    val pageIndex: Int,
    val pageCount: Int,
    val updatedAtEpochMillis: Long,
)

/**
 * Reading positions and resume points.
 *
 * Deliberately small: the reader needs to record a page, ask where to resume, list recents and drop
 * an entry. Everything else (caching covers, syncing, statistics) belongs to a later stage.
 */
interface HistoryRepository {

    /** Newest positions first. */
    fun observeRecent(limit: Int): Flow<List<ReadingHistoryEntry>>

    suspend fun record(entry: ReadingHistoryEntry)

    suspend fun progress(comicKey: ComicKey): ReadingProgress?

    suspend fun remove(comicKey: ComicKey)
}
