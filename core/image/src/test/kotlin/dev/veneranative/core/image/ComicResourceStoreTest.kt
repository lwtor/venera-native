package dev.veneranative.core.image

import dev.veneranative.core.model.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.io.File
import okio.Path.Companion.toOkioPath

class ComicResourceStoreTest {
    @get:Rule val files = TemporaryFolder()
    private val key = ComicKey(SourceId("fixture/../source"), RemoteComicId("../../comic"))
    @Test fun validatedCoverPersistsOutsideCacheAndFailedReplacementRetainsOldBytes() = runTest {
        val root = files.newFolder("resources")
        val store = ComicResourceStore(root) { it.readText().startsWith("valid") }
        val source = files.newFile("source").apply { writeText("valid-one") }
        assertTrue(store.saveCover(key, "https://example.org/cover?secret=fixture", source, 10))
        assertEquals(10L, store.fetchedAt(key, "https://example.org/cover?secret=fixture"))
        assertTrue(store.comicDir(key).canonicalPath.startsWith(root.canonicalPath + File.separator))
        source.writeText("broken")
        assertFalse(store.saveCover(key, "https://example.org/new", source, 20))
        assertEquals("valid-one", store.openCover(key)!!.use { it.file.readText() })
        source.writeText("valid-two")
        assertTrue(store.saveCover(key, "https://example.org/new", source, 30))
        assertEquals("valid-two", store.openCover(key)!!.use { it.file.readText() })
        assertTrue(root.walkTopDown().none { it.extension == "part" })
        assertTrue(root.walkTopDown().none { it.extension == "bin" })
        assertTrue(root.walkTopDown().filter { it.name == "source.sha256" }.none { it.readText().contains("secret") })
    }
    @Test fun explicitRetainedRequestWorksWithClosedNetworkCacheAndNoAuthenticationLookup() = runTest {
        val store = ComicResourceStore(files.newFolder("resources")) { it.readText() == "valid" }
        val bytes = files.newFile("saved").apply { writeText("valid") }
        assertTrue(store.saveCover(key, "https://example.org/cover", bytes, 10))
        val cache = coil3.disk.DiskCache.Builder().directory(files.newFolder("cache").toOkioPath()).maxSizeBytes(1024 * 1024).build()
        cache.shutdown()
        val pipeline = CoilComicImagePipeline(okhttp3.OkHttpClient(), cache,
            ComicImageAuthProvider { _, _ -> error("Retained artwork must not depend on an active source session") }, resources = store)
        val request = ComicImageRequest("https://example.org/cover", sourceId = key.sourceId, retainedCoverKey = key)
        assertEquals("valid", pipeline.cachedFileOf(request)!!.use { it.file.readText() })
    }

    @Test fun replacedLocalCoverAtSameUrlHasANewImageIdentity() {
        val request = ComicImageRequest("https://example.org/cover", sourceId = key.sourceId, retainedCoverKey = key, retainedCoverRevision = 10)
        assertNotEquals(ComicImageCacheKey.of(request, emptyMap()), ComicImageCacheKey.of(request.copy(retainedCoverRevision = 20), emptyMap()))
    }

    @Test fun retainedArtworkIsReusedWithoutFetchingAndRefreshFailureKeepsFallback() = runTest {
        val store = ComicResourceStore(files.newFolder("resources")) { it.readText() == "valid" }
        val bytes = files.newFile("bytes").apply { writeText("valid") }
        var calls = 0
        var fail = false
        val pipeline = object : ComicImagePipeline {
            override suspend fun cachedFileOf(request: ComicImageRequest): ComicImageFile? {
                calls++; return if (fail) null else ComicImageFile(bytes, null)
            }
            override suspend fun sizeOf(request: ComicImageRequest): ImageSize? = null
        }
        val storage = PersistentComicCoverStorage(store, pipeline, { 100L }, StandardTestDispatcher(testScheduler))
        assertEquals(100L, storage.retain(key, "https://example.org/cover"))
        assertEquals(100L, storage.retain(key, "https://example.org/cover"))
        assertEquals(1, calls)
        fail = true
        assertNull(storage.retain(key, "https://example.org/new", true))
        assertTrue(storage.available(key))
        assertEquals("valid", store.openCover(key)!!.use { it.file.readText() })
    }
}
