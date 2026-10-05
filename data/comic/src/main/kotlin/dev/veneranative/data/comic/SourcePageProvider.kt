package dev.veneranative.data.comic

import dev.veneranative.core.model.ChapterContent
import dev.veneranative.core.model.ChapterKey
import dev.veneranative.core.model.ChapterRef
import dev.veneranative.core.model.ComicPage
import dev.veneranative.core.model.PageImageSizer
import dev.veneranative.core.model.PageProvider
import dev.veneranative.core.model.SourcePage
import dev.veneranative.core.model.SourceCapability
import dev.veneranative.source.api.SourceOutcome
import dev.veneranative.source.api.SourceRuntimeError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Loads chapter references immediately; dimensions and image bytes are resolved per visible page. */
class SourcePageProvider(
    private val catalog: ComicCatalog,
    private val sizer: PageImageSizer,
    /** Display title of a chapter; the remote id is used when the caller has nothing better. */
    private val chapterTitle: suspend (ChapterKey) -> String = { chapter -> chapter.remoteId.value },
    /** Retryable source failures are retried briefly before the reader exposes its retry action. */
    private val waitBeforeBusyRetry: suspend (attempt: Int) -> Unit = { attempt -> delay(attempt * 150L) },
    /** Application-owned scope keeps chapter warm-up alive when the current reader route leaves. */
    private val prefetchScope: CoroutineScope? = null,
) : PageProvider {

    private val chapterLoadMutex = Mutex()
    private var prefetchedChapter: Pair<ChapterRef.Remote, ChapterContent>? = null

    override suspend fun loadChapter(chapter: ChapterRef): ChapterContent {
        val ref = chapter as? ChapterRef.Remote ?: throw IllegalArgumentException("Source provider only accepts remote chapters")
        return chapterLoadMutex.withLock {
            prefetchedChapter?.takeIf { it.first == ref }?.second?.also { prefetchedChapter = null }
                ?: loadChapterFromSource(ref)
        }
    }

    override suspend fun prefetchChapter(chapter: ChapterRef) {
        val ref = chapter as? ChapterRef.Remote ?: return
        val scope = prefetchScope
        if (scope != null) {
            scope.launch { runCatching { prefetchChapterNow(ref) } }
            return
        }
        prefetchChapterNow(ref)
    }

    private suspend fun prefetchChapterNow(ref: ChapterRef.Remote) {
        chapterLoadMutex.withLock {
            if (prefetchedChapter?.first == ref) return
            val content = loadChapterFromSource(ref)
            val firstPage = content.pages.firstOrNull()
            val warmed = if (firstPage == null) content else {
                try {
                    content.copy(pages = listOf(resolve(firstPage)) + content.pages.drop(1))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    content
                }
            }
            prefetchedChapter = ref to warmed
        }
    }

    private suspend fun loadChapterFromSource(ref: ChapterRef.Remote): ChapterContent {
        val key = ref.key
        val references = retrySourceCall { catalog.pages(key) }
        if (references.isEmpty()) throw IllegalStateException("Source returned no pages")
        val pages = references.mapIndexed { index, reference ->
            ComicPage(
                index = index,
                imageRef = reference.imageRef,
                widthPx = 1080,
                heightPx = 1440,
                sourceId = key.comicKey.sourceId,
                sizeState = dev.veneranative.core.model.PageSizeState.Pending,
                imageHeaders = reference.headers,
                reverseHorizontalBands = reference.reverseHorizontalBands,
            )
        }
        val detail = try {
            retrySourceCall { catalog.detail(key.comicKey) }
        } catch (failure: SourceLoadException) {
            // Some sources legitimately expose pages without comic details. A transient or
            // broken detail call is different: fail visibly so the reader can retry instead of
            // silently treating the missing nextChapter as the end of the series.
            if ((failure.error as? SourceRuntimeError.UnsupportedCapability)?.capability == SourceCapability.DETAIL) {
                null
            } else {
                throw failure
            }
        }
        val chapterList = detail?.chapters?.let { chapters ->
            ref.group?.let { selectedGroup -> chapters.filter { it.group == selectedGroup } } ?: chapters
        }.orEmpty()
        val chapterIndex = chapterList.indexOfFirst { it.key == key }
        return ChapterContent(
            title = chapterList.firstOrNull { it.key == key }?.title ?: chapterTitle(key),
            pages = pages, comicTitle = detail?.comic?.title, coverUrl = detail?.comic?.coverUrl,
            nextChapter = chapterList.getOrNull(chapterIndex + 1).takeIf { chapterIndex >= 0 },
            previousChapter = chapterList.getOrNull(chapterIndex - 1).takeIf { chapterIndex > 0 },
        )
    }

    private suspend fun <T> retrySourceCall(call: suspend () -> SourceOutcome<T>): T {
        var retries = 0
        while (true) {
            when (val outcome = call()) {
                is SourceOutcome.Success -> return outcome.value
                is SourceOutcome.Failure -> {
                    if (!outcome.error.retryable || retries >= MAX_SOURCE_RETRIES) {
                        throw SourceLoadException(outcome.error)
                    }
                    retries++
                    waitBeforeBusyRetry(retries)
                }
            }
        }
    }

    suspend fun loadChapter(chapter: ChapterKey): ChapterContent = loadChapter(ChapterRef.Remote(chapter))

    override suspend fun resolve(page: ComicPage): ComicPage {
        val sourceId = requireNotNull(page.sourceId)
        val size = sizer.sizeOf(page.imageRef, sourceId, page.imageHeaders)
            ?: throw java.io.IOException("Page image unavailable")
        return page.copy(widthPx = size.widthPx, heightPx = size.heightPx,
            sizeState = dev.veneranative.core.model.PageSizeState.Ready)
    }

    override suspend fun prefetch(page: ComicPage) { resolve(page) }

    private companion object {
        const val MAX_SOURCE_RETRIES = 2
    }
}
