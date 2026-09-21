package dev.veneranative.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * Where the reader left a comic, kept apart from the history list.
 *
 * The split matters: history is "every position ever recorded" and is read as a list, while this is
 * a single answer to "which page do I open next", keyed by comic. Keeping them in two tables means
 * resuming does not have to scan the whole history.
 */
@Entity(
    tableName = "reading_progress",
    primaryKeys = ["source_id", "comic_id"],
)
data class ReadingProgressEntity(
    @ColumnInfo(name = "source_id") val sourceId: String,
    @ColumnInfo(name = "comic_id") val comicId: String,
    @ColumnInfo(name = "chapter_id") val chapterId: String,
    @ColumnInfo(name = "page_index") val pageIndex: Int,
    @ColumnInfo(name = "updated_at") val updatedAtEpochMillis: Long,
)
