package dev.veneranative.data.settings

import dev.veneranative.core.model.ChapterKey
import dev.veneranative.core.model.ChapterRef
import dev.veneranative.core.model.ComicKey
import dev.veneranative.core.model.LocalChapterId
import dev.veneranative.core.model.LocalComicId
import dev.veneranative.core.model.RemoteChapterId
import dev.veneranative.core.model.RemoteComicId
import dev.veneranative.core.model.SourceId
import dev.veneranative.core.model.stableId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultSplitPreferenceRepositoryTest {

    private class InMemoryPreferences : ScreenPreferenceRepository {
        val map = mutableMapOf<String, String>()
        override suspend fun get(key: String): String? = map[key]
        override suspend fun put(key: String, value: String) {
            map[key] = value
        }

        override suspend fun remove(key: String) {
            map.remove(key)
        }
    }

    private val remote = ChapterRef.Remote(
        ChapterKey(
            comicKey = ComicKey(SourceId("source-a"), RemoteComicId("comic-1")),
            remoteId = RemoteChapterId("chapter-1"),
        ),
        group = "vol-1",
    )

    @Test
    fun `split enabled persists and round trips`() = runTest {
        val store = InMemoryPreferences()
        val repo = DefaultSplitPreferenceRepository(store)

        assertFalse(repo.isSplitEnabled(remote))
        repo.setSplitEnabled(remote, true)
        assertTrue(repo.isSplitEnabled(remote))
        assertEquals(1, store.map.size)

        repo.setSplitEnabled(remote, false)
        assertFalse(repo.isSplitEnabled(remote))
        assertTrue(store.map.isEmpty())
    }

    @Test
    fun `page exclusion persists and round trips per source page`() = runTest {
        val store = InMemoryPreferences()
        val repo = DefaultSplitPreferenceRepository(store)

        assertFalse(repo.isPageExcluded(remote, 0))
        assertFalse(repo.isPageExcluded(remote, 1))

        repo.setPageExcluded(remote, 0, true)
        assertTrue(repo.isPageExcluded(remote, 0))
        assertFalse(repo.isPageExcluded(remote, 1))

        repo.setPageExcluded(remote, 0, false)
        assertFalse(repo.isPageExcluded(remote, 0))
    }

    @Test
    fun `remote and local chapters never share a stable id`() = runTest {
        val local = ChapterRef.Local(LocalComicId("comic-1"), LocalChapterId("chapter-1"))
        assertNotEquals(remote.stableId(), local.stableId())
        assertTrue(remote.stableId().startsWith("remote:"))
        assertTrue(local.stableId().startsWith("local:"))
    }

    @Test
    fun `group and source participate in the stable id`() {
        val otherGroup = remote.copy(group = "vol-2")
        val otherSource = ChapterRef.Remote(
            ChapterKey(
                comicKey = ComicKey(SourceId("source-b"), RemoteComicId("comic-1")),
                remoteId = RemoteChapterId("chapter-1"),
            ),
            group = "vol-1",
        )
        val noGroup = remote.copy(group = null)

        assertNotEquals(remote.stableId(), otherGroup.stableId())
        assertNotEquals(remote.stableId(), otherSource.stableId())
        assertNotEquals(remote.stableId(), noGroup.stableId())
    }

    @Test
    fun `split and exclusion keys do not collide`() = runTest {
        val store = InMemoryPreferences()
        val repo = DefaultSplitPreferenceRepository(store)

        repo.setSplitEnabled(remote, true)
        repo.setPageExcluded(remote, 0, true)

        assertTrue(repo.isSplitEnabled(remote))
        assertTrue(repo.isPageExcluded(remote, 0))
        assertEquals(2, store.map.size)
    }
}
