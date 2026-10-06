package dev.veneranative.core.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ReadingHistoryDao {

    /** One latest chapter position per comic, ordered by the comics most recently read. */
    @Query("""
        SELECT history.* FROM reading_history AS history
        WHERE NOT EXISTS (
            SELECT 1 FROM reading_history AS newer
            WHERE newer.source_id = history.source_id
              AND newer.comic_id = history.comic_id
              AND (
                  newer.updated_at > history.updated_at
                  OR (newer.updated_at = history.updated_at AND newer.rowid > history.rowid)
              )
        )
        ORDER BY history.updated_at DESC, history.rowid DESC
        LIMIT :limit
    """)
    fun observeRecent(limit: Int): Flow<List<ReadingHistoryEntity>>

    @Query("SELECT * FROM reading_history WHERE source_id = :sourceId AND comic_id = :comicId")
    fun observeComic(sourceId: String, comicId: String): Flow<List<ReadingHistoryEntity>>

    @Query("SELECT * FROM reading_history WHERE source_id = :sourceId AND comic_id = :comicId")
    suspend fun find(sourceId: String, comicId: String): ReadingHistoryEntity?

    @Upsert suspend fun upsert(entry: ReadingHistoryEntity)

    @Query("DELETE FROM reading_history WHERE source_id = :sourceId AND comic_id = :comicId")
    suspend fun delete(sourceId: String, comicId: String)
}
