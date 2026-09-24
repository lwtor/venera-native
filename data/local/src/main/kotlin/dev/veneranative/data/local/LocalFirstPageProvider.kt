package dev.veneranative.data.local

import android.graphics.BitmapFactory
import dev.veneranative.core.model.ChapterContent
import dev.veneranative.core.model.ChapterRef
import dev.veneranative.core.model.ComicPage
import dev.veneranative.core.model.PageProvider
import dev.veneranative.core.model.PageSizeState
import dev.veneranative.core.model.LocalComicId
import dev.veneranative.core.model.LocalChapterId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

fun localReaderKey(comicId: LocalComicId, chapterId: LocalChapterId): ChapterRef = ChapterRef.Local(comicId, chapterId)

class LocalFirstPageProvider(
    private val source: PageProvider,
    private val localRepository: suspend () -> LocalComicRepository,
    private val materializer: LocalPageMaterializer,
) : PageProvider {
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
            val pages = repository.pages(comicId, chapterId).map { page ->
                val path = materializer.materialize(page)
                ComicPage(page.index, path, 1080, 1440, sourceId = null, sizeState = PageSizeState.Pending)
            }
            if (pages.isEmpty()) throw IOException("Local chapter has no readable pages")
            ChapterContent(localChapter.title, pages, comic.title, comic.coverPath)
        }
    }

    override suspend fun resolve(page: ComicPage): ComicPage {
        if (page.sourceId != null) return source.resolve(page)
        return withContext(Dispatchers.IO) {
            val file = File(page.imageRef)
            if (!file.isFile) throw IOException("Local page unavailable")
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, options)
            if (options.outWidth <= 0 || options.outHeight <= 0) throw IOException("Local page is unreadable")
            page.copy(widthPx = options.outWidth, heightPx = options.outHeight, sizeState = PageSizeState.Ready)
        }
    }

    override suspend fun prefetch(page: ComicPage) {
        if (page.sourceId != null) source.prefetch(page)
    }
}
