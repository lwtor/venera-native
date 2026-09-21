package dev.veneranative.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/**
 * One persisted point in a comic: the comic, the chapter and the page the reader stopped at.
 *
 * The identity is (source, comic, chapter) rather than a synthetic row id because a source owns
 * those strings and nothing guarantees they are stable across sources — the same remote id under
 * two sources is deliberately two different rows.
 *
 * This module does not depend on `:core:model`, so every field here is a plain column. Callers map
 * to and from domain types in `:data:history`.
 */
@Entity(
    tableName = "reading_history",
    primaryKeys = ["source_id", "comic_id", "chapter_id"],
    indices = [Index(value = ["updated_at"])],
)
data class ReadingHistoryEntity(
    @ColumnInfo(name = "source_id") val sourceId: String,
    @ColumnInfo(name = "comic_id") val comicId: String,
    @ColumnInfo(name = "chapter_id") val chapterId: String,
    @ColumnInfo(name = "comic_title") val comicTitle: String,
    @ColumnInfo(name = "chapter_title") val chapterTitle: String,
    @ColumnInfo(name = "cover_url") val coverUrl: String?,
    @ColumnInfo(name = "page_index") val pageIndex: Int,
    @ColumnInfo(name = "page_count") val pageCount: Int,
    @ColumnInfo(name = "updated_at") val updatedAtEpochMillis: Long,
)
