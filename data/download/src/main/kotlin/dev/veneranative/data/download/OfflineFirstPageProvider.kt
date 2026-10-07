package dev.veneranative.data.download

import dev.veneranative.core.image.ImageSizeHeaderParser
import dev.veneranative.core.model.ChapterContent
import dev.veneranative.core.model.Chapter
import dev.veneranative.core.model.ChapterKey
import dev.veneranative.core.model.ChapterRef
import dev.veneranative.core.model.ComicPage
import dev.veneranative.core.model.PageProvider
import dev.veneranative.core.model.PageSizeState
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.first

/** Reads an intact downloaded chapter directly from disk, falling back to its source when incomplete. */
class OfflineFirstPageProvider(
    private val downloads: suspend () -> DownloadRepository,
    private val layout: DownloadFileLayout,
    private val source: PageProvider,
) : PageProvider {

    override suspend fun loadChapter(chapter: ChapterRef): ChapterContent {
        val ref = chapter as? ChapterRef.Remote ?: return source.loadChapter(chapter)
        val key = ref.key
        val repository = downloads()
        if (!repository.isCompleteOffline(ref)) return source.loadChapter(chapter)
        val task = repository.observeTask(ref).first()
            ?: return source.loadChapter(chapter)
        val downloaded = repository.pagesOf(ref)
        if (downloaded.isEmpty() || downloaded.any { it.state != DownloadPageState.Succeeded }) {
            return source.loadChapter(chapter)
        }
        val pages = downloaded.sortedBy { it.index }.map { page ->
            val relative = page.relativePath ?: return source.loadChapter(chapter)
            val file = layout.absoluteOf(relative)
            if (!layout.isInsideRoot(file) || !file.isFile) return source.loadChapter(chapter)
            ComicPage(
                index = page.index,
                imageRef = file.absolutePath,
                widthPx = 1080,
                heightPx = 1440,
                sourceId = null,
                sizeState = PageSizeState.Pending,
            )
        }
        val sameVersion = repository.observeTasks().first()
            .filter { candidate ->
                val candidateRef = candidate.chapter as? ChapterRef.Remote
                candidateRef != null && candidateRef.key.comicKey == key.comicKey &&
                    candidateRef.group == ref.group
            }
            .filter { candidate ->
                candidate.chapter == chapter ||
                    candidate.state == DownloadChapterState.Completed && repository.isCompleteOffline(candidate.chapter)
            }
            .sortedWith(compareBy<DownloadTask>({ it.chapterIndex ?: Int.MAX_VALUE }, { it.createdAtEpochMillis }))
        val ordered = sameVersion.mapIndexed { index, candidate -> candidate.toReaderChapter(index) }
        val position = ordered.indexOfFirst { it.key == key }
        return ChapterContent(
            title = task.title,
            pages = pages,
            comicTitle = task.comicTitle,
            nextChapter = ordered.getOrNull(position + 1),
            previousChapter = ordered.getOrNull(position - 1),
        )
    }

    override suspend fun prefetchChapter(chapter: ChapterRef) {
        val key = (chapter as? ChapterRef.Remote)?.key
        if (key == null || !downloads().isCompleteOffline(chapter)) {
            source.prefetchChapter(chapter)
        }
    }

    suspend fun loadChapter(chapter: ChapterKey): ChapterContent = loadChapter(ChapterRef.Remote(chapter))

    private fun DownloadTask.toReaderChapter(fallbackIndex: Int): Chapter {
        val ref = chapter as ChapterRef.Remote
        return Chapter(key = ref.key, title = title, index = chapterIndex ?: fallbackIndex, group = ref.group)
    }

    override suspend fun resolve(page: ComicPage): ComicPage {
        if (page.sourceId != null) return source.resolve(page)
        val file = File(page.imageRef)
        if (!file.isFile || !layout.isInsideRoot(file)) throw IOException("Downloaded page unavailable")
        val header = file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            val count = input.read(buffer)
            if (count <= 0) byteArrayOf() else buffer.copyOf(count)
        }
        val size = ImageSizeHeaderParser.parse(header) ?: throw IOException("Downloaded page is unreadable")
        return page.copy(widthPx = size.widthPx, heightPx = size.heightPx, sizeState = PageSizeState.Ready)
    }

    override suspend fun prefetch(page: ComicPage) {
        if (page.sourceId == null) resolve(page) else source.prefetch(page)
    }
}
