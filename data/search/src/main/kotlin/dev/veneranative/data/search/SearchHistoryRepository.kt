package dev.veneranative.data.search

import dev.veneranative.core.database.SearchHistoryDao
import dev.veneranative.core.database.SearchHistoryEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface SearchHistoryRepository {
    fun observeRecent(): Flow<List<String>>
    suspend fun record(keyword: String)
    suspend fun remove(keyword: String)
    suspend fun clear()
}

class DefaultSearchHistoryRepository(
    private val dao: SearchHistoryDao,
    private val now: () -> Long = System::currentTimeMillis,
    private val limit: Int = DEFAULT_LIMIT,
) : SearchHistoryRepository {
    private val recordMutex = Mutex()
    init { require(limit > 0) }

    override fun observeRecent(): Flow<List<String>> = dao.observeRecent(limit).map { rows -> rows.map { it.keyword } }

    override suspend fun record(keyword: String) {
        val normalized = keyword.trim()
        if (normalized.isEmpty()) return
        recordMutex.withLock {
            val previous = dao.newestTimestamp()
            val next = previous?.let { if (it == Long.MAX_VALUE) it else it + 1L }
            dao.upsert(SearchHistoryEntity(keyword = normalized, searchedAt = maxOf(now(), next ?: Long.MIN_VALUE)))
            dao.trim(limit)
        }
    }

    override suspend fun remove(keyword: String) = dao.delete(keyword)
    override suspend fun clear() = dao.clear()

    private companion object { const val DEFAULT_LIMIT = 30 }
}
