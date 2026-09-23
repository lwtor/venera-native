package dev.veneranative.data.download

import dev.veneranative.core.database.DownloadPageEntity
import dev.veneranative.core.database.DownloadTaskEntity
import dev.veneranative.core.model.ChapterKey
import dev.veneranative.core.model.ChapterRef
import dev.veneranative.core.model.ComicKey
import dev.veneranative.core.model.LOCAL_REF_NAMESPACE
import dev.veneranative.core.model.LocalChapterId
import dev.veneranative.core.model.LocalComicId
import dev.veneranative.core.model.RemoteChapterId
import dev.veneranative.core.model.RemoteComicId
import dev.veneranative.core.model.SourceId

/**
 * Between the identity the rest of the app speaks and the three string columns the table has.
 *
 * `:core:database` cannot depend on `:core:model`, so the conversion lives here. A local chapter is
 * recorded under the reserved `@local` source, which a source script can never claim because
 * installing one with a key that starts with `@` is rejected.
 */

/** The row id of a chapter's download: the same chapter always produces the same id. */
fun ChapterRef.taskId(): String = when (this) {
    is ChapterRef.Remote ->
        "r:${key.comicKey.sourceId.value.length}:${key.comicKey.sourceId.value}" +
            ":${key.comicKey.remoteId.value}:${key.remoteId.value}"

    is ChapterRef.Local -> "l:${comicId.value}:${chapterId.value}"
}

fun refSourceOf(chapter: ChapterRef): String = when (chapter) {
    is ChapterRef.Remote -> chapter.key.comicKey.sourceId.value
    is ChapterRef.Local -> LOCAL_REF_NAMESPACE
}

fun refComicOf(chapter: ChapterRef): String = when (chapter) {
    is ChapterRef.Remote -> chapter.key.comicKey.remoteId.value
    is ChapterRef.Local -> chapter.comicId.value
}

fun refChapterOf(chapter: ChapterRef): String = when (chapter) {
    is ChapterRef.Remote -> chapter.key.remoteId.value
    is ChapterRef.Local -> chapter.chapterId.value
}

/** Null when the columns do not describe a chapter, which only corrupt data could produce. */
fun chapterRefOf(sourceId: String, comicId: String, chapterId: String): ChapterRef? =
    if (sourceId == LOCAL_REF_NAMESPACE) {
        runCatching {
            ChapterRef.Local(LocalComicId(comicId), LocalChapterId(chapterId))
        }.getOrNull()
    } else {
        runCatching {
            ChapterRef.Remote(
                ChapterKey(
                    comicKey = ComicKey(SourceId(sourceId), RemoteComicId(comicId)),
                    remoteId = RemoteChapterId(chapterId),
                ),
            )
        }.getOrNull()
    }

internal fun DownloadTaskEntity.toDomain(): DownloadTask? {
    val chapter = chapterRefOf(refSource, refComic, refChapter) ?: return null
    return DownloadTask(
        chapter = chapter,
        title = title,
        comicTitle = comicTitle,
        pageCount = pageCount,
        completedPages = completedPages,
        state = runCatching { DownloadChapterState.valueOf(state) }
            .getOrDefault(DownloadChapterState.Queued),
        createdAtEpochMillis = createdAt,
        updatedAtEpochMillis = updatedAt,
    )
}

internal fun DownloadTaskEntity.chapterOrNull(): ChapterRef? =
    chapterRefOf(refSource, refComic, refChapter)

internal fun DownloadPageEntity.toDomain(chapter: ChapterRef): DownloadPage = DownloadPage(
    chapter = chapter,
    index = pageIndex,
    imageRef = imageRef,
    state = pageState(),
    relativePath = relativePath,
    bytes = bytes,
    attempts = attempts,
    lastError = errorFromColumn(lastError),
)

internal fun DownloadPageEntity.pageState(): DownloadPageState =
    runCatching { DownloadPageState.valueOf(state) }.getOrDefault(DownloadPageState.Queued)

internal fun DownloadError.toColumn(): String = when (this) {
    DownloadError.Network -> ERROR_NETWORK
    DownloadError.StorageFull -> ERROR_STORAGE_FULL
    DownloadError.NotResolvable -> ERROR_NOT_RESOLVABLE
    is DownloadError.Corrupt -> "$ERROR_CORRUPT_PREFIX$reason"
}

internal fun errorFromColumn(column: String?): DownloadError? = when {
    column == null -> null
    column == ERROR_NETWORK -> DownloadError.Network
    column == ERROR_STORAGE_FULL -> DownloadError.StorageFull
    column == ERROR_NOT_RESOLVABLE -> DownloadError.NotResolvable
    column.startsWith(ERROR_CORRUPT_PREFIX) ->
        DownloadError.Corrupt(column.removePrefix(ERROR_CORRUPT_PREFIX))

    else -> null
}

private const val ERROR_NETWORK: String = "Network"
private const val ERROR_STORAGE_FULL: String = "StorageFull"
private const val ERROR_NOT_RESOLVABLE: String = "NotResolvable"
private const val ERROR_CORRUPT_PREFIX: String = "Corrupt:"
