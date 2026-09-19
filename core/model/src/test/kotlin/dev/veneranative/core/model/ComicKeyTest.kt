package dev.veneranative.core.model

import org.junit.Assert.assertNotEquals
import org.junit.Test

class ComicKeyTest {
    @Test
    fun sameRemoteIdFromDifferentSourcesProducesDifferentKeys() {
        val first = ComicKey(SourceId("source-a"), RemoteComicId("42"))
        val second = ComicKey(SourceId("source-b"), RemoteComicId("42"))

        assertNotEquals(first, second)
    }
}
