package dev.veneranative.core.database

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns the process-wide [VeneraDatabase].
 *
 * The database holds reading positions, the shelf, download metadata and local-library indexes, so it is not encrypted
 * and there is no destructive fallback: corruption has to surface, not silently wipe someone's
 * progress — which is why every schema step has to be registered here, or an upgrading user would
 * simply be told their database cannot be opened.
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
    )
        .addMigrations(*VENERA_DATABASE_MIGRATIONS.toTypedArray())
        // A fresh install never runs a migration, so the fallback folder is seeded here as well —
        // otherwise the shelf would have nowhere to put a comic until the user creates a folder.
        .addCallback(SeedDefaultFolder)
        .build()

    private object SeedDefaultFolder : RoomDatabase.Callback() {

        override fun onCreate(db: SupportSQLiteDatabase) {
            db.execSQL(INSERT_DEFAULT_FOLDER)
        }
    }
}
