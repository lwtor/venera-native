package dev.veneranative.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/**
 * One comic on the shelf.
 *
 * The identity is (source, comic) rather than a synthetic row id because the pair is what the rest
 * of the app addresses a comic by: a remote comic is its source plus its remote id, and an imported
 * comic is written with the reserved `@local` source namespace so both fit in the same columns.
 *
 * [chapterCount] and [latestChapterId] are the snapshot the last update check saw; [hasUpdate] is
 * the flag derived from comparing them, and [updatedAt] is when that comparison last changed the
 * flag — it exists so the shelf can sort by "recently updated" without re-deriving it in the UI.
 *
 * Ordering is a query concern, not a UI concern, which is why the columns a shelf sorts by are
 * indexed and the DAO owns the `ORDER BY`.
 */
@Entity(
    tableName = "favorite_entry",
    primaryKeys = ["ref_source", "ref_comic"],
    indices = [
        Index(value = ["folder_id"]),
        Index(value = ["added_at"]),
        Index(value = ["has_update"]),
    ],
)
data class FavoriteEntryEntity(
    /** Source id of a remote comic, or `@local` for an imported one. */
    @ColumnInfo(name = "ref_source") val refSource: String,
    @ColumnInfo(name = "ref_comic") val refComic: String,
    @ColumnInfo(name = "folder_id") val folderId: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "subtitle") val subtitle: String?,
    @ColumnInfo(name = "cover_ref") val coverRef: String?,
    @ColumnInfo(name = "added_at") val addedAt: Long,
    @ColumnInfo(name = "last_read_at") val lastReadAt: Long?,
    @ColumnInfo(name = "chapter_count") val chapterCount: Int?,
    @ColumnInfo(name = "latest_chapter_id") val latestChapterId: String?,
    @ColumnInfo(name = "has_update") val hasUpdate: Boolean,
    @ColumnInfo(name = "updated_at") val updatedAt: Long?,
)
