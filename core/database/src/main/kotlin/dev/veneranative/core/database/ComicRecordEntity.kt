package dev.veneranative.core.database

import androidx.room.*

/** Durable metadata for a retained comic, independent from collection membership and reading history. */
@Entity(tableName = "comic_record", primaryKeys = ["source_id", "comic_id"], indices = [Index("next_refresh_at")])
data class ComicRecordEntity(
    @ColumnInfo(name = "source_id") val sourceId: String,
    @ColumnInfo(name = "comic_id") val comicId: String,
    val title: String,
    val subtitle: String? = null,
    @ColumnInfo(name = "cover_url") val coverUrl: String? = null,
    val description: String? = null,
    @ColumnInfo(name = "authors_json") val authorsJson: String? = null,
    @ColumnInfo(name = "tags_json") val tagsJson: String? = null,
    @ColumnInfo(name = "publication_status") val publicationStatus: String? = null,
    @ColumnInfo(name = "source_updated_at") val sourceUpdatedAt: String? = null,
    @ColumnInfo(name = "metadata_refreshed_at") val metadataRefreshedAt: Long? = null,
    @ColumnInfo(name = "last_attempt_at") val lastAttemptAt: Long? = null,
    @ColumnInfo(name = "next_refresh_at", defaultValue = "0") val nextRefreshAt: Long = 0,
    @ColumnInfo(name = "cover_fetched_at") val coverFetchedAt: Long? = null,
    /** Raw legacy snapshot retained until the repository can normalize it without source requests. */
    @ColumnInfo(name = "legacy_chapters_json") val legacyChaptersJson: String? = null,
)

@Entity(tableName = "comic_chapter", primaryKeys = ["source_id", "comic_id", "version_key", "chapter_id"],
    foreignKeys = [ForeignKey(entity = ComicRecordEntity::class, parentColumns = ["source_id", "comic_id"],
        childColumns = ["source_id", "comic_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["source_id", "comic_id"])])
data class ComicChapterEntity(
    @ColumnInfo(name = "source_id") val sourceId: String,
    @ColumnInfo(name = "comic_id") val comicId: String,
    /** 0 = ungrouped; 1 + original group name = named group (including an empty name). */
    @ColumnInfo(name = "version_key") val versionKey: String,
    @ColumnInfo(name = "chapter_id") val chapterId: String,
    val title: String,
    val position: Int,
    @ColumnInfo(name = "group_name") val groupName: String? = null,
)
