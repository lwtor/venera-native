package dev.veneranative.data.history

import dev.veneranative.core.database.ReadingHistoryEntity
import dev.veneranative.core.database.ReadingProgressEntity
import dev.veneranative.core.model.ComicKey
import dev.veneranative.core.model.RemoteChapterId
import dev.veneranative.core.model.RemoteComicId
import dev.veneranative.core.model.SourceId

/**
 * Entity <-> domain mapping.
 *
 * `:core:database` deliberately does not know `:core:model`, so the conversion between plain columns
 * and value objects lives here and nowhere else.
 *
 * Reads are **tolerant**: a stored row whose ids are empty or blank cannot become a `SourceId`, and
 * one bad row must not take down the whole "continue reading" list, so it is dropped instead of
 * throwing. Writes keep the caller's timestamp, because ordering is a fact the caller already knows
 * and stamping it here would silently rewrite it.
 */

internal fun ReadingHistoryEntity.toDomainOrNull(): ReadingHistoryEntry? = runCatching {
    ReadingHistoryEntry(
        comicKey = ComicKey(SourceId(sourceId), RemoteComicId(comicId)),
        comicTitle = comicTitle,
        chapterId = RemoteChapterId(chapterId),
        chapterTitle = chapterTitle,
        coverUrl = coverUrl,
        pageIndex = pageIndex,
        pageCount = pageCount,
        updatedAtEpochMillis = updatedAtEpochMillis,
    )
}.getOrNull()

internal fun ReadingProgressEntity.toDomainOrNull(): ReadingProgress? = runCatching {
    ReadingProgress(
        comicKey = ComicKey(SourceId(sourceId), RemoteComicId(comicId)),
        chapterId = RemoteChapterId(chapterId),
        pageIndex = pageIndex,
        updatedAtEpochMillis = updatedAtEpochMillis,
    )
}.getOrNull()

internal fun ReadingHistoryEntry.toEntity(): ReadingHistoryEntity = ReadingHistoryEntity(
    sourceId = comicKey.sourceId.value,
    comicId = comicKey.remoteId.value,
    chapterId = chapterId.value,
    comicTitle = comicTitle,
    chapterTitle = chapterTitle,
    coverUrl = coverUrl,
    pageIndex = pageIndex,
    pageCount = pageCount,
    updatedAtEpochMillis = updatedAtEpochMillis,
)

internal fun ReadingHistoryEntry.toProgressEntity(): ReadingProgressEntity = ReadingProgressEntity(
    sourceId = comicKey.sourceId.value,
    comicId = comicKey.remoteId.value,
    chapterId = chapterId.value,
    pageIndex = pageIndex,
    updatedAtEpochMillis = updatedAtEpochMillis,
)
