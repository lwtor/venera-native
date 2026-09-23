package dev.veneranative.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One page of a download task.
 *
 * Downloading is tracked per page rather than per chapter because a chapter is tens of megabytes
 * and a phone loses its network, its battery or its process partway through: page rows are what let
 * a stopped download continue from where it stopped instead of from the beginning.
 *
 * [relativePath] is deliberately relative. An absolute path in a database row or in a UI state goes
 * stale the moment the directory moves, and it leaks device layout into layers that must not know it.
 */
@Entity(
    tableName = "download_page",
    primaryKeys = ["task_id", "page_index"],
    foreignKeys = [
        ForeignKey(
            entity = DownloadTaskEntity::class,
            parentColumns = ["task_id"],
            childColumns = ["task_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["state"])],
)
data class DownloadPageEntity(
    @ColumnInfo(name = "task_id") val taskId: String,
    @ColumnInfo(name = "page_index") val pageIndex: Int,
    @ColumnInfo(name = "image_ref") val imageRef: String,
    @ColumnInfo(name = "state") val state: String,
    @ColumnInfo(name = "relative_path") val relativePath: String?,
    @ColumnInfo(name = "bytes") val bytes: Long,
    @ColumnInfo(name = "attempts") val attempts: Int,
    @ColumnInfo(name = "last_error") val lastError: String?,
)
