package dev.veneranative.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** Optional user-created collections for a comic. The unfiltered shelf is the implicit All view. */
@Entity(
    tableName = "favorite_membership",
    primaryKeys = ["ref_source", "ref_comic", "folder_id"],
    foreignKeys = [
        ForeignKey(
            entity = FavoriteEntryEntity::class,
            parentColumns = ["ref_source", "ref_comic"],
            childColumns = ["ref_source", "ref_comic"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = FavoriteFolderEntity::class,
            parentColumns = ["folder_id"],
            childColumns = ["folder_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["folder_id"])],
)
data class FavoriteMembershipEntity(
    @ColumnInfo(name = "ref_source") val refSource: String,
    @ColumnInfo(name = "ref_comic") val refComic: String,
    @ColumnInfo(name = "folder_id") val folderId: String,
)
