package dev.veneranative.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ComicListMetadataTest {
    @Test fun `metadata supplements status and authors without replacing list title cover or existing tags`() {
        val original = Comic(ComicKey(SourceId("fixture"), RemoteComicId("comic")), "Original",
            coverUrl = "fixture://cover", tags = listOf("Existing"))
        val metadata = original.copy(title = "Other", coverUrl = "fixture://other", tags = listOf("New"),
            authors = listOf("A", "B"), publicationStatus = ComicPublicationStatus.Completed)
        assertEquals(original.copy(authors = listOf("A", "B"), publicationStatus = ComicPublicationStatus.Completed),
            original.withListMetadata(metadata))
    }
}
