package dev.veneranative.data.collection

import dev.veneranative.core.database.FavoriteEntryEntity
import dev.veneranative.core.database.FavoriteFolderEntity
import dev.veneranative.core.model.ComicKey
import dev.veneranative.core.model.ComicRef
import dev.veneranative.core.model.LOCAL_REF_NAMESPACE
import dev.veneranative.core.model.LocalComicId
import dev.veneranative.core.model.RemoteComicId
import dev.veneranative.core.model.SourceId

/**
 * Entity <-> domain mapping.
 *
 * `:core:database` deliberately does not know `:core:model`, so this is the only place a shelf row
 * becomes a [ComicRef] and back. Reads are tolerant: a row whose ids are blank cannot become a
 * source or comic id, and one bad row must not take the whole shelf down with it.
 */

internal fun FavoriteFolderEntity.toDomain(): FavoriteFolder = FavoriteFolder(
    id = folderId,
    name = name,
    sortOrder = sortOrder,
    removable = removable,
)

internal fun FavoriteEntryEntity.toDomainOrNull(): FavoriteItem? = runCatching {
    FavoriteItem(
        ref = comicRefOf(refSource, refComic),
        title = title,
        subtitle = subtitle,
        coverRef = coverRef,
        folderId = folderId,
        addedAtEpochMillis = addedAt,
        lastReadAtEpochMillis = lastReadAt,
        chapterCount = chapterCount,
        latestChapterId = latestChapterId,
        hasUpdate = hasUpdate,
    )
}.getOrNull()

/** The source column of a comic: its source id, or the reserved namespace when it is imported. */
internal fun ComicRef.refSource(): String = when (this) {
    is ComicRef.Remote -> key.sourceId.value
    is ComicRef.Local -> LOCAL_REF_NAMESPACE
}

/** The comic column of a comic: its remote id, or the local id when it is imported. */
internal fun ComicRef.refComic(): String = when (this) {
    is ComicRef.Remote -> key.remoteId.value
    is ComicRef.Local -> id.value
}

private fun comicRefOf(refSource: String, refComic: String): ComicRef = when (refSource) {
    LOCAL_REF_NAMESPACE -> ComicRef.Local(LocalComicId(refComic))
    else -> ComicRef.Remote(ComicKey(SourceId(refSource), RemoteComicId(refComic)))
}
