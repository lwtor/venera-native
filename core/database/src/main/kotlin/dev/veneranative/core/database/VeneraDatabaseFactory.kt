package dev.veneranative.core.database

import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns the process-wide [VeneraDatabase].
 *
 * The database holds no user content beyond reading positions, so it is not encrypted and there is
 * no destructive fallback: corruption has to surface, not silently wipe someone's progress.
 *
 * The double-checked lock exists because the app composes its graph from a screen that can be
 * recreated; two concurrent builders would open two connections to the same file.
 */
object VeneraDatabaseFactory {

    private const val DATABASE_NAME = "venera.db"

    private val lock = Mutex()
    @Volatile private var instance: VeneraDatabase? = null

    suspend fun get(context: Context): VeneraDatabase = instance ?: lock.withLock {
        instance ?: build(context.applicationContext).also { instance = it }
    }

    private fun build(context: Context): VeneraDatabase = Room.databaseBuilder(
        context,
        VeneraDatabase::class.java,
        DATABASE_NAME,
    ).build()
}
