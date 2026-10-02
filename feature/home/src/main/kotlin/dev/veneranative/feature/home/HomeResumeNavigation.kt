package dev.veneranative.feature.home

import dev.veneranative.core.model.ChapterKey
import dev.veneranative.core.model.ChapterRef
import dev.veneranative.core.model.LOCAL_REF_NAMESPACE
import dev.veneranative.core.model.LocalChapterId
import dev.veneranative.core.model.LocalComicId
import dev.veneranative.data.history.ReadingHistoryEntry

/** Restores the identity kind used by the reader before it was serialized into history tables. */
fun ReadingHistoryEntry.toChapterRef(): ChapterRef =
    if (comicKey.sourceId.value == LOCAL_REF_NAMESPACE) {
        ChapterRef.Local(
            comicId = LocalComicId(comicKey.remoteId.value),
            chapterId = LocalChapterId(chapterId.value),
        )
    } else {
        ChapterRef.Remote(ChapterKey(comicKey, chapterId))
    }
