package dev.veneranative.data.search

import dev.veneranative.core.database.SearchHistoryDao
import dev.veneranative.core.database.SearchHistoryEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class DefaultSearchHistoryRepositoryTest {

    @Test
    fun `records normalized terms most recent first and enforces a bounded history`() = runTest {
        val dao = FakeSearchHistoryDao()
        val repository = DefaultSearchHistoryRepository(dao, now = { 7L }, limit = 2)

        repository.record(" A ")
        repository.record("B")
        repository.record("A")
        repository.record("   ")

        assertEquals(listOf("A", "B"), repository.observeRecent().first())
    }

    private class FakeSearchHistoryDao : SearchHistoryDao {
        private val rows = linkedMapOf<String, SearchHistoryEntity>()
        private val state = MutableStateFlow<List<SearchHistoryEntity>>(emptyList())

        override fun observeRecent(limit: Int): Flow<List<SearchHistoryEntity>> = state

        override suspend fun upsert(item: SearchHistoryEntity) {
            rows[item.keyword] = item
            publish()
        }

        override suspend fun newestTimestamp(): Long? = rows.values.maxOfOrNull { it.searchedAt }

        override suspend fun delete(keyword: String) {
            rows.remove(keyword)
            publish()
        }

        override suspend fun clear() {
            rows.clear()
            publish()
        }

        override suspend fun trim(limit: Int) {
            rows.values.sortedByDescending { it.searchedAt }.drop(limit).forEach { rows.remove(it.keyword) }
            publish()
        }

        private fun publish() { state.value = rows.values.sortedByDescending { it.searchedAt } }
    }
}
