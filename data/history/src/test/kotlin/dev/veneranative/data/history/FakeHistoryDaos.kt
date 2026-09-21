package dev.veneranative.data.history

import dev.veneranative.core.database.ReadingHistoryDao
import dev.veneranative.core.database.ReadingHistoryEntity
import dev.veneranative.core.database.ReadingProgressDao
import dev.veneranative.core.database.ReadingProgressEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * In-memory stand-ins for the Room DAOs, so.repository and tracker logic can run on the JVM.
 *
 * They reproduce what the real queries do — including the fact that history allows one row per
 * chapter while progress keeps only one per comic — because those semantics are exactly what the
 * throttling and mapping tests need to observe.
 */
internal class FakeReadingHistoryDao : ReadingHistoryDao {

    val rows = MutableStateFlow<List<ReadingHistoryEntity>>(emptyList())

    override fun observeRecent(limit: Int): Flow<List<ReadingHistoryEntity>> =
        rows.map { list -> list.sortedByDescending { it.updatedAtEpochMillis }.take(limit) }

    override suspend fun find(sourceId: String, comicId: String): ReadingHistoryEntity? =
        rows.value.firstOrNull { it.sourceId == sourceId && it.comicId == comicId }

    override suspend fun upsert(entry: ReadingHistoryEntity) {
        rows.value = rows.value.filterNot { it.isSameKeyAs(entry) } + entry
    }

    override suspend fun delete(sourceId: String, comicId: String) {
        rows.value = rows.value.filterNot { it.sourceId == sourceId && it.comicId == comicId }
    }

    private fun ReadingHistoryEntity.isSameKeyAs(other: ReadingHistoryEntity) =
        sourceId == other.sourceId && comicId == other.comicId && chapterId == other.chapterId
}

internal class FakeReadingProgressDao : ReadingProgressDao {

    val rows = MutableStateFlow<List<ReadingProgressEntity>>(emptyList())

    override suspend fun find(sourceId: String, comicId: String): ReadingProgressEntity? =
        rows.value.firstOrNull { it.sourceId == sourceId && it.comicId == comicId }

    override suspend fun upsert(progress: ReadingProgressEntity) {
        rows.value = rows.value.filterNot { it.isSameKeyAs(progress) } + progress
    }

    override suspend fun delete(sourceId: String, comicId: String) {
        rows.value = rows.value.filterNot { it.sourceId == sourceId && it.comicId == comicId }
    }

    private fun ReadingProgressEntity.isSameKeyAs(other: ReadingProgressEntity) =
        sourceId == other.sourceId && comicId == other.comicId
}
