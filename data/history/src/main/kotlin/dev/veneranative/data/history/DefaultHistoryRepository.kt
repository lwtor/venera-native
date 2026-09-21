package dev.veneranative.data.history

import androidx.room.withTransaction
import dev.veneranative.core.database.VeneraDatabase
import dev.veneranative.core.database.ReadingHistoryDao
import dev.veneranative.core.database.ReadingProgressDao
import dev.veneranative.core.model.ComicKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Reading history and resume points over Room.
 *
 * One [record] call writes both tables: a history row is what a "continue reading" list shows, the
 * progress row is the single answer to "which page do I open next". Writing them together is what
 * keeps them from ever disagreeing about where a comic was left.
 *
 * Timestamps come from the caller. The repository does not re-stamp them, because "this position is
 * newer than that one" is something the caller knows and a second clock would only overwrite it —
 * including across processes, where a restored session's older timestamp must stay older.
 */
class DefaultHistoryRepository internal constructor(
    private val historyDao: ReadingHistoryDao,
    private val progressDao: ReadingProgressDao,
    private val transaction: suspend (suspend () -> Unit) -> Unit = { it() },
) : HistoryRepository {
    constructor(database: VeneraDatabase) : this(
        database.readingHistoryDao(), database.readingProgressDao(),
        { operation -> database.withTransaction { operation() } },
    )

    /**
     * Newest positions first, mapped to domain values.
     *
     * [limit] is applied by the query, so a malformed row consumes a slot before being dropped;
     * that is accepted — bad rows are the exception, and detecting them after loading everything
     * would cost more than it is worth.
     */
    override fun observeRecent(limit: Int): Flow<List<ReadingHistoryEntry>> =
        historyDao.observeRecent(limit).map { rows -> rows.mapNotNull { it.toDomainOrNull() } }

    override suspend fun record(entry: ReadingHistoryEntry) {
        transaction {
            historyDao.upsert(entry.toEntity())
            progressDao.upsert(entry.toProgressEntity())
        }
    }

    override suspend fun progress(comicKey: ComicKey): ReadingProgress? =
        progressDao.find(comicKey.sourceId.value, comicKey.remoteId.value)?.toDomainOrNull()

    /** Both tables, or the reader would still resume into a comic the user asked to forget. */
    override suspend fun remove(comicKey: ComicKey) {
        val sourceId = comicKey.sourceId.value
        val comicId = comicKey.remoteId.value
        transaction {
            historyDao.delete(sourceId, comicId)
            progressDao.delete(sourceId, comicId)
        }
    }
}
