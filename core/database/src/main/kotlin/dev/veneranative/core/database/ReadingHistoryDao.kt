package dev.veneranative.core.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ReadingHistoryDao {

    /** Recent positions, newest first. */
    @Query("SELECT * FROM reading_history ORDER BY updated_at DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ReadingHistoryEntity>>

    @Query("SELECT * FROM reading_history WHERE source_id = :sourceId AND comic_id = :comicId")
    suspend fun find(sourceId: String, comicId: String): ReadingHistoryEntity?

    @Upsert suspend fun upsert(entry: ReadingHistoryEntity)

    @Query("DELETE FROM reading_history WHERE source_id = :sourceId AND comic_id = :comicId")
    suspend fun delete(sourceId: String, comicId: String)
}
