package dev.veneranative.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One chapter the user asked to download.
 *
 * The identity is three plain string columns rather than a value object, because this module does
 * not depend on `:core:model`: a chapter is encoded the way `:core:model` describes and decoded in
 * `:data:download`. That also leaves room for local chapters later without touching the schema.
 *
 * [state] stores an enum **name**: adding a state later cannot renumber the ones already on disk.
 */
@Entity(tableName = "download_task", indices = [Index(value = ["updated_at"])])
data class DownloadTaskEntity(
    @PrimaryKey @ColumnInfo(name = "task_id") val taskId: String,
    @ColumnInfo(name = "ref_source") val refSource: String,
    @ColumnInfo(name = "ref_comic") val refComic: String,
    @ColumnInfo(name = "ref_chapter") val refChapter: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "comic_title") val comicTitle: String?,
    @ColumnInfo(name = "page_count") val pageCount: Int,
    @ColumnInfo(name = "completed_pages") val completedPages: Int,
    @ColumnInfo(name = "state") val state: String,
    /** Which worker owns this task right now; null when nobody has claimed it yet. */
    @ColumnInfo(name = "worker_id") val workerId: String?,
    @ColumnInfo(name = "heartbeat_at") val heartbeatAt: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
