package dev.veneranative.data.collection

import androidx.room.withTransaction
import dev.veneranative.core.database.*
import dev.veneranative.core.model.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

internal data class StoredComic(val record: ComicRecordEntity, val chapters: List<ComicChapterEntry>)

/** Canonical metadata/directory store; legacy favorite columns remain only a compatibility projection. */
internal class CollectionRecordStore(
    private val records: ComicRecordDao,
    private val favorites: FavoriteDao,
    private val clock: () -> Long,
    private val transaction: suspend (suspend () -> Unit) -> Unit = { it() },
    private val io: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Default,
) {
    constructor(db: VeneraDatabase, clock: () -> Long) : this(db.comicRecordDao(), db.favoriteDao(), clock, { block -> db.withTransaction { block() } })
    val snapshots: Flow<Map<Pair<String, String>, StoredComic>> = combine(records.observeFavorites(), records.observeFavoriteChapters()) { books, chapters ->
        val grouped = chapters.groupBy { it.sourceId to it.comicId }
        books.associate { book -> (book.sourceId to book.comicId) to StoredComic(book,
            grouped[book.sourceId to book.comicId]?.map { ComicChapterEntry(it.chapterId, it.title, it.groupName) }
                ?: decodeChapterCatalog(book.legacyChaptersJson)) }
    }.flowOn(io)
    suspend fun find(ref: ComicRef): ComicRecordEntity? = records.record(ref.refSource(), ref.refComic())
    suspend fun seed(favorite: FavoriteEntryEntity) {
        transaction {
            var record = records.record(favorite.refSource, favorite.refComic)
            if (record == null) {
                record = ComicRecordEntity(favorite.refSource, favorite.refComic, favorite.title, favorite.subtitle,
                    favorite.coverRef, sourceUpdatedAt = favorite.lastUpdated, legacyChaptersJson = favorite.chaptersJson)
                records.upsert(record)
            }
            record.legacyChaptersJson?.let { raw ->
                val entries = decodeChapterCatalog(raw)
                if (entries.isNotEmpty()) {
                    replaceDirectory(record.sourceId, record.comicId, entries)
                    records.upsert(record.copy(legacyChaptersJson = null))
                }
            }
        }
    }
    suspend fun directory(ref: ComicRef): List<ComicChapterEntry> {
        val rows = records.chapters(ref.refSource(), ref.refComic())
        return if (rows.isNotEmpty()) rows.map { ComicChapterEntry(it.chapterId, it.title, it.groupName) }
        else decodeChapterCatalog(find(ref)?.legacyChaptersJson)
    }
    suspend fun saveSnapshot(ref: ComicRef, snapshot: ComicSnapshot) {
        snapshot.detail?.let { saveDetail(ref, it); return }
        transaction {
            val old = find(ref) ?: return@transaction
            records.upsert(old.copy(title = snapshot.title, subtitle = snapshot.subtitle ?: old.subtitle,
                coverUrl = snapshot.coverRef?.takeIf(String::isNotBlank) ?: old.coverUrl, sourceUpdatedAt = snapshot.lastUpdated ?: old.sourceUpdatedAt))
            snapshot.chapters?.takeIf { it.isNotEmpty() }?.let { saveDirectory(ref, it) }
        }
    }
    suspend fun saveDetail(ref: ComicRef, detail: ComicDetail) {
        val now = clock()
        transaction {
            val old = find(ref) ?: return@transaction
            records.upsert(old.copy(title = detail.comic.title, subtitle = detail.comic.subtitle ?: old.subtitle,
                coverUrl = detail.comic.coverUrl?.takeIf(String::isNotBlank) ?: old.coverUrl, description = detail.description ?: old.description,
                authorsJson = detail.comic.authors.takeIf { it.isNotEmpty() }?.let(::stringArray) ?: old.authorsJson,
                tagsJson = detail.tagGroups.takeIf { it.isNotEmpty() }?.let { groups -> buildJsonObject {
                    groups.forEach { (name, tags) -> put(name, JsonArray(tags.map(::JsonPrimitive))) }
                }.toString() } ?: old.tagsJson,
                publicationStatus = detail.comic.publicationStatus?.name ?: old.publicationStatus,
                sourceUpdatedAt = detail.comic.lastUpdated ?: detail.metadata["Updated"] ?: old.sourceUpdatedAt,
                metadataRefreshedAt = now, lastAttemptAt = now, nextRefreshAt = now + ComicRefreshPolicy.TTL_MILLIS))
            if (detail.chapters.isNotEmpty()) saveDirectory(ref, detail.chapters)
            favorites.entry(ref.refSource(), ref.refComic())?.let { favorite ->
                favorites.upsertEntry(favorite.copy(title = detail.comic.title, subtitle = detail.comic.subtitle ?: favorite.subtitle,
                    coverRef = detail.comic.coverUrl ?: favorite.coverRef,
                    lastUpdated = detail.comic.lastUpdated ?: detail.metadata["Updated"] ?: favorite.lastUpdated))
            }
        }
    }
    suspend fun saveDirectory(ref: ComicRef, chapters: List<Chapter>) {
        if (chapters.isEmpty()) return
        transaction {
            val old = find(ref) ?: return@transaction
            replaceDirectory(old.sourceId, old.comicId, chapters.map { ComicChapterEntry(it.key.remoteId.value, it.title, it.group) })
            records.upsert(old.copy(legacyChaptersJson = null))
            favorites.updateChapterCatalog(old.sourceId, old.comicId, encodeChapterCatalog(chapters))
        }
    }
    private suspend fun replaceDirectory(source: String, comic: String, entries: List<ComicChapterEntry>) {
        records.deleteChapters(source, comic)
        records.upsertChapters(entries.mapIndexed { index, chapter -> ComicChapterEntity(source, comic,
            chapter.version?.let { "1$it" } ?: "0", chapter.id, chapter.title, index, chapter.version) })
    }
    suspend fun schedule(ref: ComicRef, failed: Boolean) = records.schedule(ref.refSource(), ref.refComic(), clock(),
        clock() + if (failed) ComicRefreshPolicy.FAILURE_DELAY_MILLIS else ComicRefreshPolicy.TTL_MILLIS)
    suspend fun coverFetched(ref: ComicRef, at: Long) = records.coverFetched(ref.refSource(), ref.refComic(), at)
    private fun stringArray(values: List<String>): String = JsonArray(values.map(::JsonPrimitive)).toString()
}

internal object ComicRefreshPolicy {
    const val TTL_MILLIS = 12 * 60 * 60 * 1000L
    const val FAILURE_DELAY_MILLIS = 15 * 60 * 1000L
    fun due(nextRefreshAt: Long, now: Long): Boolean = nextRefreshAt <= now
    fun backedOff(lastAttemptAt: Long?, nextRefreshAt: Long, now: Long): Boolean =
        lastAttemptAt != null && nextRefreshAt > now && nextRefreshAt - lastAttemptAt <= FAILURE_DELAY_MILLIS
}
