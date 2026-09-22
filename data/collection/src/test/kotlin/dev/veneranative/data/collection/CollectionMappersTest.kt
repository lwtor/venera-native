package dev.veneranative.data.collection

import dev.veneranative.core.database.FavoriteEntryEntity
import dev.veneranative.core.database.FavoriteFolderEntity
import dev.veneranative.core.model.ComicKey
import dev.veneranative.core.model.ComicRef
import dev.veneranative.core.model.LOCAL_REF_NAMESPACE
import dev.veneranative.core.model.LocalComicId
import dev.veneranative.core.model.RemoteComicId
import dev.veneranative.core.model.SourceId
import dev.veneranative.core.model.comicKeyOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The one place a shelf row becomes a domain value.
 *
 * Both kinds of comic live in the same two columns, so what matters is that the reserved namespace
 * round-trips and that a row the app cannot understand is dropped rather than taking the shelf down.
 */
class CollectionMappersTest {

    private fun row(
        refSource: String = "source-a",
        refComic: String = "comic-1",
        folderId: String = "default",
        title: String = "Comic One",
    ) = FavoriteEntryEntity(
        refSource = refSource,
        refComic = refComic,
        folderId = folderId,
        title = title,
        subtitle = null,
        coverRef = null,
        addedAt = 1_000L,
        lastReadAt = null,
        chapterCount = 10,
        latestChapterId = "ch-10",
        hasUpdate = false,
        updatedAt = null,
    )

    @Test fun `a remote row keeps the source it came from`() {
        val item = row().toDomainOrNull()

        assertEquals(
            ComicKey(SourceId("source-a"), RemoteComicId("comic-1")),
            item?.ref?.comicKeyOrNull(),
        )
        assertEquals("Comic One", item?.title)
        assertEquals(10, item?.chapterCount)
    }

    @Test fun `a row in the reserved namespace is an imported comic`() {
        val item = row(refSource = LOCAL_REF_NAMESPACE, refComic = "local-1").toDomainOrNull()

        assertEquals(ComicRef.Local(LocalComicId("local-1")), item?.ref)
        assertNull(item?.ref?.comicKeyOrNull())
    }

    @Test fun `both kinds of comic round trip through the two columns`() {
        val remote = ComicRef.Remote(ComicKey(SourceId("source-a"), RemoteComicId("comic-1")))
        val local = ComicRef.Local(LocalComicId("local-1"))

        assertEquals(remote, row(refSource = remote.refSource(), refComic = remote.refComic()).toDomainOrNull()?.ref)
        assertEquals(local, row(refSource = local.refSource(), refComic = local.refComic()).toDomainOrNull()?.ref)
    }

    @Test fun `a row whose ids are unusable is dropped instead of breaking the shelf`() {
        assertNull(row(refSource = "  ", refComic = "comic-1").toDomainOrNull())
        assertNull(row(refSource = "source-a", refComic = "").toDomainOrNull())
    }

    @Test fun `a folder row carries whether it may be deleted`() {
        val folder = FavoriteFolderEntity(
            folderId = "default", name = "Default", sortOrder = 0, removable = false,
        ).toDomain()

        assertEquals(FavoriteFolder(id = "default", name = "Default", sortOrder = 0, removable = false), folder)
    }
}
