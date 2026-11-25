package dev.veneranative.core.navigation

import dev.veneranative.core.model.ChapterKey
import dev.veneranative.core.model.ChapterRef
import dev.veneranative.core.model.ComicKey
import dev.veneranative.core.model.LocalChapterId
import dev.veneranative.core.model.LocalComicId
import dev.veneranative.core.model.RemoteChapterId
import dev.veneranative.core.model.RemoteComicId
import dev.veneranative.core.model.SourceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReaderDetailsDestinationTest {
    private val comic = ComicKey(SourceId("fixture"), RemoteComicId("comic"))
    private val reader = AppRoute.Reader(ChapterRef.Remote(ChapterKey(comic, RemoteChapterId("chapter"))))
    private val details = AppRoute.ComicDetails(comic, backTo = AppRoute.Search("fixture"))

    @Test fun `details entry returns to the list that opened the original details`() {
        val target = readerDetailsDestination(reader, details)!!
        assertEquals(AppRoute.ComicDetails(comic, backTo = AppRoute.Search("fixture")), target)
        assertEquals(AppRoute.Search("fixture"), backDestination(target))
    }

    @Test fun `direct reading opens details that return to the reader's own entry`() {
        for (entry in listOf(AppRoute.Home, AppRoute.Library, AppRoute.Downloads)) {
            val target = readerDetailsDestination(reader, entry)!!
            assertEquals(entry, backDestination(target))
        }
    }

    @Test fun `another comic details must not be reused and local reading has no remote details`() {
        // A reader sitting on top of some other details screen keeps that screen's own back target
        // rather than linking the new details back to a comic the user was not reading.
        val other = AppRoute.ComicDetails(ComicKey(SourceId("fixture"), RemoteComicId("other")))
        assertEquals(
            AppRoute.ComicDetails(comic, backTo = null),
            readerDetailsDestination(reader, other),
        )
        assertNull(
            readerDetailsDestination(
                AppRoute.Reader(ChapterRef.Local(LocalComicId("local"), LocalChapterId("chapter"))),
                AppRoute.Library,
            ),
        )
    }
}
