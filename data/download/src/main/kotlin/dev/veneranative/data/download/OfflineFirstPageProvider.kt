package dev.veneranative.data.download

import dev.veneranative.core.image.ImageSizeHeaderParser
import dev.veneranative.core.model.ChapterContent
import dev.veneranative.core.model.ChapterKey
import dev.veneranative.core.model.ChapterNeighbors
import dev.veneranative.core.model.ChapterRef
import dev.veneranative.core.model.ComicPage
import dev.veneranative.core.model.PageProvider
import dev.veneranative.core.model.PageSizeState
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Reads an intact downloaded chapter directly from disk, falling back to its source when incomplete. */
class OfflineFirstPageProvider(
    private val downloads: suspend () -> DownloadRepository,
    private val layout: DownloadFileLayout,
    private val source: PageProvider,
    private val onDiagnostic: (String) -> Unit = {},
    /**
     * The source's own chapter order, asked online so a downloaded chapter still knows the
     * neighbours that were never downloaded.
     *
     * Downloaded tasks are only the chapters the user asked for, so using them as the series order
     * made a downloaded chapter look like the last one of the whole comic. Null (or a failed or
     * timed-out lookup) keeps the downloaded order as the offline fallback.
     */
    private val chapterNeighbors: (suspend (ChapterRef.Remote) -> ChapterNeighbors?)? = null,
) : PageProvider {

    override suspend fun loadChapter(chapter: ChapterRef): ChapterContent {
        val ref = chapter as? ChapterRef.Remote ?: return source.loadChapter(chapter)
        val key = ref.key
        val repository = downloads()
        onDiagnostic("event=load source=${key.comicKey.sourceId.value} comic=${key.comicKey.remoteId.value} chapter=${key.remoteId.value} requestedGroup=${ref.group ?: "<none>"}")
        val task = completedTaskFor(ref, repository)
        if (task == null) {
            onDiagnostic("event=decision chapter=${key.remoteId.value} decision=online reason=no-completed-download")
            return source.loadChapter(chapter)
        }
        val offlineRef = task.chapter as? ChapterRef.Remote
            ?: throw IOException("Downloaded chapter reference is not remote")
        onDiagnostic("event=decision chapter=${key.remoteId.value} decision=offline taskGroup=${offlineRef.group ?: "<legacy-none>"} pages=${task.pageCount}")
        if (!repository.isCompleteOffline(offlineRef)) {
            onDiagnostic("event=offline-invalid chapter=${key.remoteId.value} reason=missing-or-corrupt-file")
            throw IOException("Downloaded chapter files are missing or damaged")
        }
        val downloaded = repository.pagesOf(offlineRef)
        if (downloaded.isEmpty() || downloaded.any { it.state != DownloadPageState.Succeeded }) {
            onDiagnostic("event=offline-invalid chapter=${key.remoteId.value} reason=page-state")
            throw IOException("Downloaded chapter has incomplete pages")
        }
        val pages = downloaded.sortedBy { it.index }.map { page ->
            val relative = page.relativePath
                ?: throw IOException("Downloaded page has no stored file")
            val file = layout.absoluteOf(relative)
            if (!layout.isInsideRoot(file) || !file.isFile) {
                throw IOException("Downloaded page file is unavailable")
            }
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
                    candidateRef.group == offlineRef.group
            }
            .filter { candidate ->
                    candidate.chapter == offlineRef ||
                    candidate.state == DownloadChapterState.Completed && repository.isCompleteOffline(candidate.chapter)
            }
            .sortedWith(compareBy<DownloadTask>({ it.chapterIndex ?: Int.MAX_VALUE }, { it.createdAtEpochMillis }))
        val ordered = sameVersion.map { it.chapter as ChapterRef.Remote }
        val position = ordered.indexOf(offlineRef)
        val onlineNeighbors = onlineNeighbors(offlineRef)
        // A successful lookup is authoritative: it can legitimately say "this is the last chapter".
        // Only a missing or failed lookup falls back to the downloaded order, which is also guarded
        // against an unmapped position so an unknown chapter never inherits a neighbour by accident.
        val next = if (onlineNeighbors != null) {
            onlineNeighbors.nextChapter
        } else {
            ordered.getOrNull(position + 1).takeIf { position >= 0 }
        }
        val previous = if (onlineNeighbors != null) {
            onlineNeighbors.previousChapter
        } else {
            ordered.getOrNull(position - 1).takeIf { position > 0 }
        }
        onDiagnostic(
            "event=offline-ready chapter=${key.remoteId.value} pages=${pages.size} " +
                "versionChapters=${ordered.size} neighbors=${if (onlineNeighbors != null) "source" else "downloaded"}",
        )
        return ChapterContent(
            title = task.title,
            pages = pages,
            comicTitle = task.comicTitle,
            nextChapter = next,
            previousChapter = previous,
            // Without the source order, the downloaded list is all we know: no next chapter there
            // means "nothing downloaded after this one", not "the series ended".
            neighborsResolved = onlineNeighbors != null || next != null,
        )
    }

    override suspend fun prefetchChapter(chapter: ChapterRef) {
        val ref = chapter as? ChapterRef.Remote
        if (ref == null) return source.prefetchChapter(chapter)
        val repository = downloads()
        if (completedTaskFor(ref, repository) == null) source.prefetchChapter(chapter)
    }

    /** Matches exact version identity first, then accepts a pre-versioning task with no group. */
    private suspend fun completedTaskFor(
        chapter: ChapterRef.Remote,
        repository: DownloadRepository,
    ): DownloadTask? {
        val candidates = repository.observeTasks().first().filter { candidate ->
            val ref = candidate.chapter as? ChapterRef.Remote
            ref?.key == chapter.key
        }
        return candidates.firstOrNull {
            it.chapter == chapter && it.state == DownloadChapterState.Completed
        } ?: candidates.singleOrNull { candidate ->
            val ref = candidate.chapter as? ChapterRef.Remote
            chapter.group != null && ref?.group == null && candidate.state == DownloadChapterState.Completed
        }
    }

    suspend fun loadChapter(chapter: ChapterKey): ChapterContent = loadChapter(ChapterRef.Remote(chapter))

    /**
     * Asks for the source-declared neighbours without ever failing the chapter load: an offline
     * device, an unsupported detail capability or a slow source just leaves the downloaded order.
     */
    private suspend fun onlineNeighbors(ref: ChapterRef.Remote): ChapterNeighbors? {
        val lookup = chapterNeighbors ?: return null
        return try {
            withTimeoutOrNull(CHAPTER_NEIGHBORS_TIMEOUT_MILLIS) { lookup(ref) }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }
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

/**
 * Short enough that opening a downloaded chapter offline is not held hostage by a source that never
 * answers, long enough for one detail request on a normal connection.
 */
private const val CHAPTER_NEIGHBORS_TIMEOUT_MILLIS = 5_000L
