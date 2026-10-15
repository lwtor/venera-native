package dev.veneranative.core.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ComicRecordDao {
    @Query("SELECT * FROM comic_record WHERE EXISTS (SELECT 1 FROM favorite_entry f WHERE f.ref_source = source_id AND f.ref_comic = comic_id)")
    fun observeFavorites(): Flow<List<ComicRecordEntity>>
    @Query("SELECT * FROM comic_chapter WHERE EXISTS (SELECT 1 FROM favorite_entry f WHERE f.ref_source = source_id AND f.ref_comic = comic_id) ORDER BY position")
    fun observeFavoriteChapters(): Flow<List<ComicChapterEntity>>
    @Query("SELECT * FROM comic_record WHERE source_id = :sourceId AND comic_id = :comicId")
    suspend fun record(sourceId: String, comicId: String): ComicRecordEntity?
    @Query("SELECT * FROM comic_chapter WHERE source_id = :sourceId AND comic_id = :comicId ORDER BY position")
    suspend fun chapters(sourceId: String, comicId: String): List<ComicChapterEntity>
    @Upsert suspend fun upsert(record: ComicRecordEntity)
    @Upsert suspend fun upsertChapters(chapters: List<ComicChapterEntity>)
    @Query("DELETE FROM comic_chapter WHERE source_id = :sourceId AND comic_id = :comicId")
    suspend fun deleteChapters(sourceId: String, comicId: String)
    @Query("SELECT r.* FROM comic_record r INNER JOIN favorite_entry f ON f.ref_source = r.source_id AND f.ref_comic = r.comic_id WHERE r.next_refresh_at <= :now AND r.source_id != '@local' ORDER BY r.metadata_refreshed_at, r.source_id, r.comic_id")
    suspend fun dueFavorites(now: Long): List<ComicRecordEntity>
    @Query("UPDATE comic_record SET last_attempt_at = :attemptAt, next_refresh_at = :nextAt WHERE source_id = :sourceId AND comic_id = :comicId")
    suspend fun schedule(sourceId: String, comicId: String, attemptAt: Long, nextAt: Long)
    @Query("UPDATE comic_record SET cover_fetched_at = :fetchedAt WHERE source_id = :sourceId AND comic_id = :comicId")
    suspend fun coverFetched(sourceId: String, comicId: String, fetchedAt: Long)
}
