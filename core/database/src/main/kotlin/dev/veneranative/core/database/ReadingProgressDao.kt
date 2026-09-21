package dev.veneranative.core.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface ReadingProgressDao {

    @Query("SELECT * FROM reading_progress WHERE source_id = :sourceId AND comic_id = :comicId")
    suspend fun find(sourceId: String, comicId: String): ReadingProgressEntity?

    @Upsert suspend fun upsert(progress: ReadingProgressEntity)

    /**
     * Dropping a comic from history has to drop its resume point too, otherwise the reader would
     * still open a chapter the user asked to forget.
     */
    @Query("DELETE FROM reading_progress WHERE source_id = :sourceId AND comic_id = :comicId")
    suspend fun delete(sourceId: String, comicId: String)
}
