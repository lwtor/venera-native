package dev.veneranative.data.download

import dev.veneranative.core.database.DownloadPageEntity
import dev.veneranative.core.database.DownloadTaskEntity
import dev.veneranative.core.model.ChapterKey
import dev.veneranative.core.model.ChapterRef
import dev.veneranative.core.model.ComicKey
import dev.veneranative.core.model.RemoteChapterId
import dev.veneranative.core.model.RemoteComicId
import dev.veneranative.core.model.SourceId
import dev.veneranative.core.model.SourcePage

/** The chapter every test downloads, unless it says otherwise. */
internal fun remoteChapter(
    source: String = "manga_dex",
    comic: String = "frieren",
    chapter: String = "ch-1",
): ChapterRef = ChapterRef.Remote(
    ChapterKey(
        comicKey = ComicKey(SourceId(source), RemoteComicId(comic)),
        remoteId = RemoteChapterId(chapter),
    ),
)

/**
 * A 24-byte PNG: the signature and the IHDR chunk, which is all the header parser reads.
 *
 * A real page is megabytes; the validator only ever looks at the head, so a fixture that stops
 * after the size is both enough and a hundred thousand times cheaper than a real image.
 */
internal fun pngBytes(widthPx: Int, heightPx: Int): ByteArray {
    val bytes = ByteArray(24)
    byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A).copyInto(bytes, 0)
    bytes[8] = 0
    bytes[9] = 0
    bytes[10] = 0
    bytes[11] = 0x0D // IHDR is 13 bytes long.
    byteArrayOf(0x49, 0x48, 0x44, 0x52).copyInto(bytes, 12)
    bytes.writeInt32BigEndian(16, widthPx)
    bytes.writeInt32BigEndian(20, heightPx)
    return bytes
}

/** What a hotlink guard answers with: a 200 that is not an image. */
internal fun notAnImageBytes(): ByteArray =
    "<!DOCTYPE html><html><body>403 hotlink denied</body></html>".toByteArray(Charsets.UTF_8)

internal fun sourcePages(count: Int, first: Int = 0): List<SourcePage> =
    (first until first + count).map { SourcePage(index = it, imageRef = "https://cdn.test/$it.png") }

internal fun taskEntity(
    chapter: ChapterRef,
    workerId: String? = null,
    heartbeatAt: Long = 0L,
    createdAt: Long = 1_000L,
    state: DownloadChapterState = DownloadChapterState.Queued,
    pageCount: Int = 0,
): DownloadTaskEntity = DownloadTaskEntity(
    taskId = chapter.taskId(),
    refSource = refSourceOf(chapter),
    refComic = refComicOf(chapter),
    refChapter = refChapterOf(chapter),
    title = "Chapter 1",
    comicTitle = "Frieren",
    pageCount = pageCount,
    completedPages = 0,
    state = state.name,
    workerId = workerId,
    heartbeatAt = heartbeatAt,
    createdAt = createdAt,
    updatedAt = createdAt,
)

internal fun pageEntity(
    taskId: String,
    index: Int,
    state: DownloadPageState = DownloadPageState.Queued,
    relativePath: String? = null,
    bytes: Long = 0L,
    attempts: Int = 0,
    lastError: String? = null,
): DownloadPageEntity = DownloadPageEntity(
    taskId = taskId,
    pageIndex = index,
    imageRef = "https://cdn.test/$index.png",
    state = state.name,
    relativePath = relativePath,
    bytes = bytes,
    attempts = attempts,
    lastError = lastError,
)

private fun ByteArray.writeInt32BigEndian(offset: Int, value: Int) {
    this[offset] = (value ushr 24).toByte()
    this[offset + 1] = (value ushr 16).toByte()
    this[offset + 2] = (value ushr 8).toByte()
    this[offset + 3] = value.toByte()
}
