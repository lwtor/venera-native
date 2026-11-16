package dev.veneranative.data.download

import dev.veneranative.core.database.DownloadPageEntity
import dev.veneranative.core.database.DownloadTaskEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import dev.veneranative.core.model.ChapterContent
import dev.veneranative.core.model.ChapterNeighbors
import dev.veneranative.core.model.ChapterKey
import dev.veneranative.core.model.ComicPage
import dev.veneranative.core.model.PageProvider
import dev.veneranative.core.model.ChapterRef
import dev.veneranative.core.model.RemoteChapterId

class OfflineFirstPageProviderTest {
    @get:Rule val folder = TemporaryFolder()
    private val chapterRef = remoteChapter()
    private val chapter = (chapterRef as dev.veneranative.core.model.ChapterRef.Remote).key

    @Test fun `complete downloaded chapter is served from disk without a source id`() = runTest {
        val layout = DownloadFileLayout(folder.root)
        val dao = FakeDownloadDao()
        val file = layout.pageFile(refSourceOf(chapterRef), refComicOf(chapterRef), refChapterOf(chapterRef), 0)
        val bytes = pngBytes(640, 960)
        layout.writeAtomically(file, bytes)
        dao.tasks.value = listOf(taskEntity(chapterRef, state = DownloadChapterState.Completed, pageCount = 1))
        dao.pages.value = listOf(pageEntity(chapterRef.taskId(), 0, DownloadPageState.Succeeded, layout.relativeOf(file), bytes.size.toLong()))
        val source = RecordingPageProvider()
        val provider = OfflineFirstPageProvider({ DefaultDownloadRepository(dao, layout) }, layout, source)

        val content = provider.loadChapter(chapter)
        val resolved = provider.resolve(content.pages.single())

        assertEquals("Chapter 1", content.title)
        assertEquals(file.absolutePath, content.pages.single().imageRef)
        assertNull(content.pages.single().sourceId)
        assertEquals(640, resolved.widthPx)
        assertEquals(960, resolved.heightPx)
        assertEquals(0, source.calls)
    }

    @Test fun `incomplete download falls back to the source provider`() = runTest {
        val layout = DownloadFileLayout(folder.root)
        val source = RecordingPageProvider()
        val provider = OfflineFirstPageProvider({ DefaultDownloadRepository(FakeDownloadDao(), layout) }, layout, source)

        val content = provider.loadChapter(chapter)

        assertEquals("Online", content.title)
        assertEquals(1, source.calls)
    }

    @Test fun `legacy ungrouped download is used for a grouped reader chapter`() = runTest {
        val layout = DownloadFileLayout(folder.root)
        val dao = FakeDownloadDao()
        val legacyRef = remoteChapter()
        val groupedRef = ChapterRef.Remote(chapter, group = "繁中")
        val file = layout.pageFile(refSourceOf(legacyRef), refComicOf(legacyRef), refChapterOf(legacyRef), 0)
        val bytes = pngBytes(640, 960)
        layout.writeAtomically(file, bytes)
        dao.tasks.value = listOf(taskEntity(legacyRef, state = DownloadChapterState.Completed, pageCount = 1))
        dao.pages.value = listOf(pageEntity(legacyRef.taskId(), 0, DownloadPageState.Succeeded, layout.relativeOf(file), bytes.size.toLong()))
        val source = RecordingPageProvider()
        val diagnostics = mutableListOf<String>()
        val provider = OfflineFirstPageProvider({ DefaultDownloadRepository(dao, layout) }, layout, source, diagnostics::add)

        val content = provider.loadChapter(groupedRef)

        assertEquals(file.absolutePath, content.pages.single().imageRef)
        assertEquals(0, source.calls)
        assertTrue(diagnostics.any { "decision=offline" in it && "taskGroup=<legacy-none>" in it })
    }

    @Test fun `offline next and previous chapters stay inside the downloaded version`() = runTest {
        val layout = DownloadFileLayout(folder.root)
        val dao = FakeDownloadDao()
        val comic = chapter.comicKey
        fun ref(id: String, group: String) = ChapterRef.Remote(
            ChapterKey(comic, dev.veneranative.core.model.RemoteChapterId(id)), group,
        )
        val previous = ref("ch-0", "繁中")
        val current = ref("ch-1", "繁中")
        val next = ref("ch-2", "繁中")
        val otherVersion = ref("ch-3", "英文")
        val refs = listOf(previous, current, next, otherVersion)
        val tasks = refs.mapIndexed { index, ref ->
            taskEntity(ref, state = DownloadChapterState.Completed, pageCount = 1, createdAt = 1_000L + index)
                .copy(chapterIndex = if (ref.group == "繁中") index else 0, title = "Chapter $index")
        }
        val pageRows = refs.map { ref ->
            val file = layout.pageFile(ref.key.comicKey.sourceId.value, ref.key.comicKey.remoteId.value, ref.key.remoteId.value, 0, ref.group)
            val bytes = pngBytes(640, 960)
            layout.writeAtomically(file, bytes)
            pageEntity(ref.taskId(), 0, DownloadPageState.Succeeded, layout.relativeOf(file), bytes.size.toLong())
        }
        dao.tasks.value = tasks
        dao.pages.value = pageRows
        val provider = OfflineFirstPageProvider({ DefaultDownloadRepository(dao, layout) }, layout, RecordingPageProvider())

        val content = provider.loadChapter(current)

        val resolvedNext = content.nextChapter as ChapterRef.Remote
        val resolvedPrevious = content.previousChapter as ChapterRef.Remote
        assertEquals("ch-2", resolvedNext.key.remoteId.value)
        assertEquals("ch-0", resolvedPrevious.key.remoteId.value)
        assertEquals("繁中", resolvedNext.group)
        assertEquals("繁中", resolvedPrevious.group)
    }


    @Test fun `undownloaded neighbour still chains through the source chapter order`() = runTest {
        val layout = DownloadFileLayout(folder.root)
        val dao = FakeDownloadDao()
        val comic = chapter.comicKey
        fun ref(id: String) = ChapterRef.Remote(
            ChapterKey(comic, RemoteChapterId(id)), "繁中",
        )
        val current = ref("ch-1")
        val notDownloaded = ref("ch-2")
        val file = layout.pageFile(
            current.key.comicKey.sourceId.value, current.key.comicKey.remoteId.value, current.key.remoteId.value, 0, current.group,
        )
        val bytes = pngBytes(640, 960)
        layout.writeAtomically(file, bytes)
        dao.tasks.value = listOf(
            taskEntity(current, state = DownloadChapterState.Completed, pageCount = 1)
                .copy(chapterIndex = 1, title = "Chapter 1"),
        )
        dao.pages.value = listOf(
            pageEntity(current.taskId(), 0, DownloadPageState.Succeeded, layout.relativeOf(file), bytes.size.toLong()),
        )
        val provider = OfflineFirstPageProvider(
            { DefaultDownloadRepository(dao, layout) },
            layout,
            RecordingPageProvider(),
            chapterNeighbors = { ChapterNeighbors(previousChapter = null, nextChapter = notDownloaded) },
        )

        val content = provider.loadChapter(current)

        assertEquals(notDownloaded, content.nextChapter)
        assertNull(content.previousChapter)
    }

    @Test fun `failed neighbour lookup falls back to the downloaded order`() = runTest {
        val layout = DownloadFileLayout(folder.root)
        val dao = FakeDownloadDao()
        val comic = chapter.comicKey
        fun ref(id: String) = ChapterRef.Remote(
            ChapterKey(comic, RemoteChapterId(id)), "繁中",
        )
        val current = ref("ch-1")
        val laterDownload = ref("ch-3")
        val tasks = mutableListOf<DownloadTaskEntity>()
        val pageRows = mutableListOf<DownloadPageEntity>()
        listOf(current to 1, laterDownload to 3).forEach { (ref, index) ->
            val file = layout.pageFile(
                ref.key.comicKey.sourceId.value, ref.key.comicKey.remoteId.value, ref.key.remoteId.value, 0, ref.group,
            )
            val bytes = pngBytes(640, 960)
            layout.writeAtomically(file, bytes)
            tasks += taskEntity(ref, state = DownloadChapterState.Completed, pageCount = 1)
                .copy(chapterIndex = index, title = "Chapter $index")
            pageRows += pageEntity(ref.taskId(), 0, DownloadPageState.Succeeded, layout.relativeOf(file), bytes.size.toLong())
        }
        dao.tasks.value = tasks
        dao.pages.value = pageRows
        val provider = OfflineFirstPageProvider(
            { DefaultDownloadRepository(dao, layout) },
            layout,
            RecordingPageProvider(),
            chapterNeighbors = { throw java.io.IOException("offline") },
        )

        val content = provider.loadChapter(current)

        assertEquals(laterDownload, content.nextChapter)
    }

    private class RecordingPageProvider : PageProvider {
        var calls = 0
        override suspend fun loadChapter(chapter: ChapterRef): ChapterContent {
            calls++
            return ChapterContent("Online", emptyList())
        }
        override suspend fun resolve(page: ComicPage): ComicPage = page
    }
}
