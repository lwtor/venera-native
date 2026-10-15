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
            FavoriteEntryBackup("source-a", "comic-9", "default", "作品", null, "https://example.org/cover", 10, null, 12, "c-12", false, 20, lastUpdated = "2026-10-14", chaptersJson = "[]", description = "Fixture summary", authorsJson = "[]", tagsJson = "{}", publicationStatus = "Ongoing", metadataRefreshedAtMillis = 1234),
        ),
        favoriteMemberships = listOf(FavoriteMembershipBackup("source-a", "comic-9", "f-1")),
        readingHistory = listOf(ReadingHistoryBackup("source-a", "comic-9", "c-12", "作品", "第十二话", null, 3, 20, 100, chapterGroup = "B")),
        readingProgress = listOf(ReadingProgressBackup("source-a", "comic-9", "c-12", 3, 100)),
    )

    @Test fun `archive decrypts and preserves selected structured data`() {
        val encoded = BackupArchiveCodec.encode(snapshot, PASSWORD)

        assertEquals(snapshot, BackupArchiveCodec.decode(encoded, PASSWORD))
        assertNotEquals(snapshot.toString().toByteArray().toList(), encoded.toList())
    }

    @Test fun `legacy encrypted archive without source update date restores`() {
        // Fixed synthetic archive contents, using the old JSON format with no lastUpdated field.
        val legacyJson = """{"schemaVersion":1,"createdAtEpochMillis":1,"categories":["Favorites"],
            "favoriteFolders":[],"favoriteMemberships":[],"readingHistory":[],"readingProgress":[],"appPreferences":[],
            "favoriteEntries":[{"sourceId":"fixture","comicId":"one","legacyFolderId":"","title":"Comic",
            "subtitle":null,"coverRef":null,"addedAtEpochMillis":1,"lastReadAtEpochMillis":null,"chapterCount":null,
            "latestChapterId":null,"hasUpdate":false,"updatedAtEpochMillis":null}]}""".toByteArray()
        val salt = ByteArray(16)
        val nonce = ByteArray(12)
        val header = java.io.ByteArrayOutputStream().also { bytes ->
            java.io.DataOutputStream(bytes).use {
                it.write(byteArrayOf(0x56, 0x4e, 0x42, 0x4b)); it.writeByte(1); it.writeInt(210_000)
                it.write(salt); it.write(nonce)
            }
        }.toByteArray()
        val compressed = java.io.ByteArrayOutputStream().also { bytes ->
            java.util.zip.GZIPOutputStream(bytes).use { it.write(legacyJson) }
        }.toByteArray()
        val key = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(javax.crypto.spec.PBEKeySpec(PASSWORD, salt, 210_000, 256)).encoded
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"), javax.crypto.spec.GCMParameterSpec(128, nonce))
            updateAAD(header)
        }
        val restored = BackupArchiveCodec.decode(header + cipher.doFinal(compressed), PASSWORD)
        assertEquals("Comic", restored.favoriteEntries.single().title)
        assertEquals(null, restored.favoriteEntries.single().lastUpdated)
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
