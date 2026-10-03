package dev.veneranative.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.Index
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "search_history", primaryKeys = ["keyword"], indices = [Index("searchedAt")])
data class SearchHistoryEntity(
    val keyword: String,
    val searchedAt: Long,
)

@Dao
interface SearchHistoryDao {
    @Query("SELECT * FROM search_history ORDER BY searchedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<SearchHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: SearchHistoryEntity)

    @Query("SELECT MAX(searchedAt) FROM search_history")
    suspend fun newestTimestamp(): Long?

    @Query("DELETE FROM search_history WHERE keyword = :keyword")
    suspend fun delete(keyword: String)

    @Query("DELETE FROM search_history")
    suspend fun clear()

    @Query("DELETE FROM search_history WHERE keyword NOT IN (SELECT keyword FROM search_history ORDER BY searchedAt DESC LIMIT :limit)")
    suspend fun trim(limit: Int)
}
