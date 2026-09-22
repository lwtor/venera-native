package dev.veneranative.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Id of the folder every comic falls back to.
 *
 * Seeded together with the table by the migration and by the first-open callback, so a favourite
 * always has somewhere to live even before the user creates a folder.
 */
const val DEFAULT_FOLDER_ID: String = "default"

/** Display name of [DEFAULT_FOLDER_ID] at creation time; the user may rename it. */
const val DEFAULT_FOLDER_NAME: String = "Default"

/**
 * A folder the user sorts favourites into.
 *
 * [removable] marks the seeded default folder: it is the only one that cannot be deleted, because
 * comics the user never filed anywhere still have to live somewhere.
 *
 * This module does not depend on `:core:model`, so every field is a plain column and callers map to
 * and from domain types in `:data:collection`.
 */
@Entity(
    tableName = "favorite_folder",
    indices = [Index(value = ["sort_order"])],
)
data class FavoriteFolderEntity(
    @PrimaryKey @ColumnInfo(name = "folder_id") val folderId: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
    @ColumnInfo(name = "removable") val removable: Boolean,
)
