package dev.veneranative.data.local

import dev.veneranative.core.model.ChapterContent
import dev.veneranative.core.model.ChapterRef
import dev.veneranative.core.model.ComicPage
import dev.veneranative.core.model.PageProvider
import dev.veneranative.core.model.PageSizeState
import dev.veneranative.core.model.LocalComicId
import dev.veneranative.core.model.LocalChapterId
import dev.veneranative.core.image.ImageSizeHeaderParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

fun localReaderKey(comicId: LocalComicId, chapterId: LocalChapterId): ChapterRef = ChapterRef.Local(comicId, chapterId)

class LocalFirstPageProvider(
    private val source: PageProvider,
    private val localRepository: suspend () -> LocalComicRepository,
    private val materializer: LocalPageMaterializer,
) : PageProvider {
    private val pendingPages = ConcurrentHashMap<String, LocalPage>()
    private val materializedPages = ConcurrentHashMap<String, LocalPage>()

    override suspend fun loadChapter(chapter: ChapterRef): ChapterContent {
        val localRef = chapter as? ChapterRef.Local ?: return source.loadChapter(chapter)
        return withContext(Dispatchers.IO) {
            val comicId = localRef.comicId
            val chapterId = localRef.chapterId
            val repository = localRepository()
            val localChapter = repository.observeChapters(comicId).first().firstOrNull { it.id == chapterId }
                ?: throw IOException("Local chapter unavailable")
            val comic = repository.observeComics().first().firstOrNull { it.id == comicId }
                ?: throw IOException("Local comic unavailable")
            pendingPages.clear()
            materializedPages.clear()
            val pages = repository.pages(comicId, chapterId).map { page ->
                val key = "local-page:${comicId.value}:${chapterId.value}:${page.index}"
                pendingPages[key] = page
                ComicPage(page.index, key, 1080, 1440, sourceId = null, sizeState = PageSizeState.Pending)
            }
            if (pages.isEmpty()) throw IOException("Local chapter has no readable pages")
            ChapterContent(localChapter.title, pages, comic.title, comic.coverPath)
        }
    }

    override suspend fun resolve(page: ComicPage): ComicPage {
        if (page.sourceId != null) return source.resolve(page)
        return withContext(Dispatchers.IO) {
            val localPage = pendingPages[page.imageRef] ?: materializedPages[page.imageRef]
            val path = localPage?.let(materializer::materialize) ?: page.imageRef
            val file = File(path)
            if (!file.isFile) throw IOException("Local page unavailable")
            val header = file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                val count = input.read(buffer)
                if (count <= 0) byteArrayOf() else buffer.copyOf(count)
            }
            val size = ImageSizeHeaderParser.parse(header) ?: throw IOException("Local page is unreadable")
            if (localPage != null) materializedPages[path] = localPage
            page.copy(imageRef = path, widthPx = size.widthPx, heightPx = size.heightPx, sizeState = PageSizeState.Ready)
        }
    }

    override suspend fun prefetch(page: ComicPage) {
        if (page.sourceId != null) source.prefetch(page)
        else if (materializedPages.containsKey(page.imageRef) && !File(page.imageRef).isFile) {
            withContext(Dispatchers.IO) { materializedPages[page.imageRef]?.let(materializer::materialize) }
        }
    }
}
