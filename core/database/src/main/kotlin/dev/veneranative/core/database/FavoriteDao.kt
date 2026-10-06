package dev.veneranative.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Favourite folders and their comics.
 *
 * Sorting is expressed as one query per shelf order rather than a single query taking a sort
 * string: SQLite cannot bind an `ORDER BY` column, and composing SQL in the caller is exactly the
 * mistake this layer exists to avoid. The repository picks the query, so no caller ever sorts rows
 * in memory and the order the user sees is the order the database returns.
 *
 * A null [folderId] means the implicit All view; a non-null id filters via membership.
 */
@Dao
interface FavoriteDao {

    @Query("SELECT * FROM favorite_folder ORDER BY sort_order ASC, name ASC")
    fun observeFolders(): Flow<List<FavoriteFolderEntity>>

    @Query("SELECT * FROM favorite_folder WHERE folder_id = :folderId")
    suspend fun folder(folderId: String): FavoriteFolderEntity?

    @Query("SELECT * FROM favorite_folder")
    suspend fun folders(): List<FavoriteFolderEntity>

    @Query("SELECT MAX(sort_order) FROM favorite_folder")
    suspend fun maxFolderSortOrder(): Int?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertFolder(folder: FavoriteFolderEntity)

    @Query("UPDATE favorite_folder SET name = :name WHERE folder_id = :folderId")
    suspend fun renameFolder(folderId: String, name: String)

    @Query("DELETE FROM favorite_folder WHERE folder_id = :folderId")
    suspend fun deleteFolder(folderId: String)

    @Query(
        "SELECT * FROM favorite_entry WHERE (:folderId IS NULL OR EXISTS (SELECT 1 FROM favorite_membership m WHERE m.ref_source = favorite_entry.ref_source AND m.ref_comic = favorite_entry.ref_comic AND m.folder_id = :folderId)) " +
            "ORDER BY added_at DESC",
    )
    fun observeByAddedAt(folderId: String?): Flow<List<FavoriteEntryEntity>>

    @Query(
        "SELECT * FROM favorite_entry WHERE (:folderId IS NULL OR EXISTS (SELECT 1 FROM favorite_membership m WHERE m.ref_source = favorite_entry.ref_source AND m.ref_comic = favorite_entry.ref_comic AND m.folder_id = :folderId)) " +
            "ORDER BY title COLLATE NOCASE ASC",
    )
    fun observeByTitle(folderId: String?): Flow<List<FavoriteEntryEntity>>

    @Query(
        "SELECT favorite_entry.* FROM favorite_entry " +
            "WHERE (:folderId IS NULL OR EXISTS (SELECT 1 FROM favorite_membership m WHERE m.ref_source = favorite_entry.ref_source AND m.ref_comic = favorite_entry.ref_comic AND m.folder_id = :folderId)) " +
            "ORDER BY COALESCE((SELECT MAX(reading_history.updated_at) FROM reading_history " +
            "WHERE reading_history.source_id = favorite_entry.ref_source " +
            "AND reading_history.comic_id = favorite_entry.ref_comic), favorite_entry.last_read_at) " +
            "IS NULL ASC, COALESCE((SELECT MAX(reading_history.updated_at) FROM reading_history " +
            "WHERE reading_history.source_id = favorite_entry.ref_source " +
            "AND reading_history.comic_id = favorite_entry.ref_comic), favorite_entry.last_read_at) " +
            "DESC, favorite_entry.added_at DESC",
    )
    fun observeByLastRead(folderId: String?): Flow<List<FavoriteEntryEntity>>

    @Query(
        "SELECT * FROM favorite_entry WHERE (:folderId IS NULL OR EXISTS (SELECT 1 FROM favorite_membership m WHERE m.ref_source = favorite_entry.ref_source AND m.ref_comic = favorite_entry.ref_comic AND m.folder_id = :folderId)) " +
            "ORDER BY has_update DESC, updated_at DESC, added_at DESC",
    )
    fun observeByUpdate(folderId: String?): Flow<List<FavoriteEntryEntity>>

    @Upsert
    suspend fun upsertEntry(entry: FavoriteEntryEntity)

    @Query("SELECT * FROM favorite_membership")
    fun observeMemberships(): Flow<List<FavoriteMembershipEntity>>

    @Query("SELECT folder_id FROM favorite_membership WHERE ref_source = :refSource AND ref_comic = :refComic")
    suspend fun folderIds(refSource: String, refComic: String): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMembership(membership: FavoriteMembershipEntity)

    @Query("DELETE FROM favorite_membership WHERE ref_source = :refSource AND ref_comic = :refComic")
    suspend fun deleteMemberships(refSource: String, refComic: String)

    @Query("SELECT * FROM favorite_entry WHERE ref_source = :refSource AND ref_comic = :refComic")
    suspend fun entry(refSource: String, refComic: String): FavoriteEntryEntity?

    /**
     * One comic's shelf row, or null while it is not on the shelf.
     *
     * A screen that offers "keep this comic" asks the database rather than remembering what it last
     * wrote: the answer then stays right when the comic is removed from the shelf somewhere else.
     */
    @Query("SELECT * FROM favorite_entry WHERE ref_source = :refSource AND ref_comic = :refComic")
    fun observeEntry(refSource: String, refComic: String): Flow<FavoriteEntryEntity?>

    /** Every favourite, for an update sweep. */
    @Query("SELECT * FROM favorite_entry")
    suspend fun entries(): List<FavoriteEntryEntity>

    @Query("DELETE FROM favorite_entry WHERE ref_source = :refSource AND ref_comic = :refComic")
    suspend fun deleteEntry(refSource: String, refComic: String)

    @Query(
        "UPDATE favorite_entry SET has_update = 0 " +
            "WHERE ref_source = :refSource AND ref_comic = :refComic",
    )
    suspend fun clearUpdate(refSource: String, refComic: String)

    /**
     * Applies the chapter snapshot an update check produced.
     *
     * [hasUpdate] is passed instead of always being set: clearing the flag and recording the new
     * baseline are two different operations, and collapsing them would let a refresh silently
     * re-raise a flag the user had just dismissed.
     */
    @Query(
        "UPDATE favorite_entry SET chapter_count = :chapterCount, " +
            "latest_chapter_id = :latestChapterId, has_update = :hasUpdate, updated_at = :updatedAt " +
            "WHERE ref_source = :refSource AND ref_comic = :refComic",
    )
    suspend fun applySnapshot(
        refSource: String,
        refComic: String,
        chapterCount: Int?,
        latestChapterId: String?,
        hasUpdate: Boolean,
        updatedAt: Long,
    )
}
