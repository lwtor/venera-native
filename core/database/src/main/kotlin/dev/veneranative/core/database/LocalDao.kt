package dev.veneranative.core.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface LocalDao {
    @Query("SELECT * FROM local_grant ORDER BY granted_at DESC")
    fun observeGrants(): Flow<List<LocalGrantEntity>>
    @Query("SELECT * FROM local_grant ORDER BY granted_at DESC")
    suspend fun grants(): List<LocalGrantEntity>
    @Upsert suspend fun upsertGrant(grant: LocalGrantEntity)
    @Query("DELETE FROM local_grant WHERE uri = :uri") suspend fun deleteGrant(uri: String)

    @Query("SELECT * FROM local_comic WHERE root_uri = :uri")
    suspend fun comicForGrant(uri: String): LocalComicEntity?
    @Query("SELECT * FROM local_comic ORDER BY title COLLATE NOCASE, added_at")
    fun observeComics(): Flow<List<LocalComicEntity>>
    @Query("SELECT * FROM local_comic WHERE comic_id = :comicId")
    suspend fun comic(comicId: String): LocalComicEntity?
    @Upsert suspend fun upsertComic(comic: LocalComicEntity)
    @Query("DELETE FROM local_comic WHERE comic_id = :comicId") suspend fun deleteComic(comicId: String)

    @Query("SELECT * FROM local_chapter WHERE comic_id = :comicId ORDER BY sort_index")
    fun observeChapters(comicId: String): Flow<List<LocalChapterEntity>>
    @Query("SELECT * FROM local_chapter WHERE comic_id = :comicId AND chapter_id = :chapterId")
    suspend fun chapter(comicId: String, chapterId: String): LocalChapterEntity?
    @Upsert suspend fun upsertChapters(chapters: List<LocalChapterEntity>)
    @Query("DELETE FROM local_chapter WHERE comic_id = :comicId") suspend fun deleteChapters(comicId: String)

    @Query("SELECT * FROM local_page WHERE comic_id = :comicId ORDER BY chapter_id, page_index")
    suspend fun pagesForComic(comicId: String): List<LocalPageEntity>
    @Query("SELECT * FROM local_page WHERE comic_id = :comicId AND chapter_id = :chapterId ORDER BY page_index")
    suspend fun pages(comicId: String, chapterId: String): List<LocalPageEntity>
    @Upsert suspend fun upsertPages(pages: List<LocalPageEntity>)
    @Query("DELETE FROM local_page WHERE comic_id = :comicId") suspend fun deletePages(comicId: String)
}
