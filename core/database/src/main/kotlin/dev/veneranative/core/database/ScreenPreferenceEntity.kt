package dev.veneranative.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/** Small key/value state that should survive leaving a screen and process recreation. */
@Entity(tableName = "screen_preference")
data class ScreenPreferenceEntity(
    @PrimaryKey val key: String,
    val value: String,
)

@Dao
interface ScreenPreferenceDao {
    @Query("SELECT * FROM `screen_preference`")
    suspend fun all(): List<ScreenPreferenceEntity>

    @Query("DELETE FROM `screen_preference`")
    suspend fun deleteAll()

    @Query("SELECT `value` FROM `screen_preference` WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(preference: ScreenPreferenceEntity)

    @Query("DELETE FROM `screen_preference` WHERE `key` = :key")
    suspend fun remove(key: String)
}
