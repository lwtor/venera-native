package dev.veneranative.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Id of the former default folder, retained for old migration steps. */
const val DEFAULT_FOLDER_ID: String = "default"

/** Former default name, retained for old migration steps. */
const val DEFAULT_FOLDER_NAME: String = "Default"

/**
 * A folder the user sorts favourites into.
 *
 * All is an implicit view over favorite entries. This table now holds only user collections;
 * [removable] remains in the schema for existing data.
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
