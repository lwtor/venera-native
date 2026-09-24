package dev.veneranative.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.veneranative.core.archive.ArchiveEntry
import dev.veneranative.core.archive.ArchiveReader
import dev.veneranative.core.database.VeneraDatabase
import java.io.ByteArrayInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalArchiveRefreshTest {
    @Test
    fun refreshingAnArchiveKeepsItsIndexedPages() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), VeneraDatabase::class.java,
        ).build()
        try {
            val repository = DefaultLocalComicRepository(database, object : SafTreeAccess {
                override fun take(uri: String) = Unit
                override fun release(uri: String) = Unit
                override fun read(uri: String): TreeNode? = error("archive must not be scanned as a tree")
            }, archiveAccess = LocalArchiveAccess { archive() })
            val imported = repository.importArchive("content://comic.cbz") as LocalImportResult.Imported

            repository.refresh(imported.comic.id)

            assertEquals(LocalKind.Archive, repository.observeComics().first().single().kind)
            assertEquals(1, database.localDao().pages(imported.comic.id.value,
                repository.observeChapters(imported.comic.id).first().single().id.value).size)
        } finally {
            database.close()
        }
    }

    @Test
    fun cancellingArchiveImportPropagatesCancellation() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), VeneraDatabase::class.java,
        ).build()
        try {
            val repository = DefaultLocalComicRepository(database, object : SafTreeAccess {
                override fun take(uri: String) = Unit
                override fun release(uri: String) = Unit
                override fun read(uri: String): TreeNode? = null
            }, archiveAccess = LocalArchiveAccess { throw CancellationException("cancelled") })

            assertThrows(CancellationException::class.java) {
                runBlocking { repository.importArchive("content://comic.cbz") }
            }
        } finally {
            database.close()
        }
    }

    private fun archive(): ArchiveReader = object : ArchiveReader {
        override fun entries() = listOf(ArchiveEntry("001.png", 24, false))
        override fun openEntry(name: String) = ByteArrayInputStream(byteArrayOf())
        override fun close() = Unit
    }
}
