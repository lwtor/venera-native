package dev.veneranative.core.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupArchiveCodecTest {
    private val snapshot = BackupSnapshot(
        createdAtEpochMillis = 1_700_000_000_000,
        categories = setOf(BackupCategory.Favorites, BackupCategory.ReadingHistory),
        favoriteFolders = listOf(FavoriteFolderBackup("f-1", "想看", 2, true)),
        favoriteEntries = listOf(
            FavoriteEntryBackup("source-a", "comic-9", "default", "作品", null, "https://example.org/cover", 10, null, 12, "c-12", false, 20),
        ),
        favoriteMemberships = listOf(FavoriteMembershipBackup("source-a", "comic-9", "f-1")),
        readingHistory = listOf(ReadingHistoryBackup("source-a", "comic-9", "c-12", "作品", "第十二话", null, 3, 20, 100)),
        readingProgress = listOf(ReadingProgressBackup("source-a", "comic-9", "c-12", 3, 100)),
    )

    @Test fun `archive decrypts and preserves selected structured data`() {
        val encoded = BackupArchiveCodec.encode(snapshot, PASSWORD)

        assertEquals(snapshot, BackupArchiveCodec.decode(encoded, PASSWORD))
        assertNotEquals(snapshot.toString().toByteArray().toList(), encoded.toList())
    }

    @Test fun `wrong password and modified ciphertext are rejected`() {
        val encoded = BackupArchiveCodec.encode(snapshot, PASSWORD)
        val modified = encoded.copyOf().apply { this[this.lastIndex] = (this.last().toInt() xor 1).toByte() }

        assertThrows(IllegalArgumentException::class.java) {
            BackupArchiveCodec.decode(encoded, "different password".toCharArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackupArchiveCodec.decode(modified, PASSWORD)
        }
    }

    @Test fun `archive requires a nontrivial passphrase`() {
        assertThrows(IllegalArgumentException::class.java) {
            BackupArchiveCodec.encode(snapshot, "short".toCharArray())
        }
    }

    private companion object {
        val PASSWORD = "correct horse battery staple".toCharArray()
    }
}
