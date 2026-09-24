package dev.veneranative.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "local_grant")
data class LocalGrantEntity(
    @PrimaryKey @ColumnInfo(name = "uri") val uri: String,
    @ColumnInfo(name = "kind") val kind: String,
    @ColumnInfo(name = "granted_at") val grantedAt: Long,
)

@Entity(tableName = "local_comic", indices = [Index(value = ["added_at"])])
data class LocalComicEntity(
    @PrimaryKey @ColumnInfo(name = "comic_id") val comicId: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "kind") val kind: String,
    @ColumnInfo(name = "root_uri") val rootUri: String,
    @ColumnInfo(name = "cover_path") val coverPath: String?,
    @ColumnInfo(name = "chapter_count") val chapterCount: Int,
    @ColumnInfo(name = "added_at") val addedAt: Long,
)

@Entity(tableName = "local_chapter", primaryKeys = ["comic_id", "chapter_id"])
data class LocalChapterEntity(
    @ColumnInfo(name = "comic_id") val comicId: String,
    @ColumnInfo(name = "chapter_id") val chapterId: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "sort_index") val sortIndex: Int,
    @ColumnInfo(name = "entry_name") val entryName: String?,
)

@Entity(
    tableName = "local_page",
    primaryKeys = ["comic_id", "chapter_id", "page_index"],
    indices = [Index(value = ["comic_id", "chapter_id"])],
)
data class LocalPageEntity(
    @ColumnInfo(name = "comic_id") val comicId: String,
    @ColumnInfo(name = "chapter_id") val chapterId: String,
    @ColumnInfo(name = "page_index") val pageIndex: Int,
    @ColumnInfo(name = "entry_name") val entryName: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
)
