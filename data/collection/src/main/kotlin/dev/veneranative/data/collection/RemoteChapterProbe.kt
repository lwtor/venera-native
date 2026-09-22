package dev.veneranative.data.collection

import dev.veneranative.core.model.ComicKey
import dev.veneranative.data.comic.ComicCatalog
import dev.veneranative.source.api.SourceOutcome

/**
 * A comic's chapter list, reduced to the two facts update detection can compare.
 *
 * Sources do not publish timestamps, so "is there something new" can only be answered by comparing
 * what the source says now with what it said last time.
 */
data class ChapterSnapshot(
    val chapterCount: Int,
    /**
     * Id of the chapter the source lists last.
     *
     * A source owns its order — some publish oldest first, some newest first — so the last entry is
     * only meaningful as "what the source ended with last time". It catches a chapter being
     * replaced at the same count; a growing count is caught either way.
     */
    val latestChapterId: String?,
)

/**
 * Asks the outside world what a comic's chapters look like right now.
 *
 * The repository deliberately does not know where the answer comes from: the shelf must not grow a
 * dependency on a source runtime, and a test can answer with a fixed snapshot.
 */
interface RemoteChapterProbe {

    /** Null when the comic's source cannot answer right now: uninstalled, offline, unsupported. */
    suspend fun chapterSnapshot(comicKey: ComicKey): ChapterSnapshot?
}

/**
 * The probe backed by installed sources.
 *
 * It reuses [ComicCatalog.detail], the same single call the details screen makes, so a refresh costs
 * one request per favourite and never invents a second protocol path.
 */
class ComicCatalogChapterProbe(
    private val catalog: ComicCatalog,
) : RemoteChapterProbe {

    override suspend fun chapterSnapshot(comicKey: ComicKey): ChapterSnapshot? {
        val detail = when (val outcome = catalog.detail(comicKey)) {
            is SourceOutcome.Success -> outcome.value
            is SourceOutcome.Failure -> return null
        }
        val chapters = detail.chapters
        // A comic with no chapters has nothing to compare; reporting zero would look like "empty"
        // rather than "unknown" and would mark everything as updated on the next real answer.
        if (chapters.isEmpty()) return null
        return ChapterSnapshot(
            chapterCount = chapters.size,
            latestChapterId = chapters.last().key.remoteId.value,
        )
    }
}
