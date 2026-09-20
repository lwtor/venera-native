package dev.veneranative.source.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The convention is copied from upstream, so these tests record the upstream rules — including the
 * unfriendly one: an indented class declaration is rejected there too.
 */
class SourceClassConventionTest {

    @Test
    fun findsTheClassNameTheWayUpstreamDoes() {
        assertEquals(
            "MangaDex",
            SourceClassConvention.classNameOf("class MangaDex extends ComicSource {\n}"),
        )
        assertEquals(
            "Local",
            SourceClassConvention.classNameOf("// a comment\nclass Local extends ComicSource {\n}"),
        )
        assertEquals(
            "WithSuffix",
            SourceClassConvention.classNameOf("class WithSuffix extends ComicSource {\r\n}\r\n"),
        )
    }

    @Test
    fun rejectsScriptsThatDoNotFollowTheConvention() {
        assertNull(SourceClassConvention.classNameOf("function main() {}"))
        assertNull(SourceClassConvention.classNameOf("  class Indented extends ComicSource {}"))
        assertNull(SourceClassConvention.classNameOf("class NotASource {}"))
        assertNull(SourceClassConvention.classNameOf("class extends ComicSource {}"))
    }

    @Test
    fun keysFollowTheUpstreamAlphabet() {
        assertTrue(SourceClassConvention.isUsableKey("manga_dex"))
        assertTrue(SourceClassConvention.isUsableKey("Source9"))
        assertFalse(SourceClassConvention.isUsableKey("manga-dex"))
        assertFalse(SourceClassConvention.isUsableKey("manga.dex"))
        assertFalse(SourceClassConvention.isUsableKey("manga dex"))
        assertFalse(SourceClassConvention.isUsableKey(""))
    }
}
