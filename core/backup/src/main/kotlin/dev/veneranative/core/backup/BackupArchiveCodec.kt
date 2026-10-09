package dev.veneranative.core.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull

/** Compresses and encrypts a backup before it can leave the device. */
object BackupArchiveCodec {
    private val magic = byteArrayOf(0x56, 0x4E, 0x42, 0x4B) // VNBK
    private const val FORMAT_VERSION = 1
    private const val KDF_ITERATIONS = 210_000
    private const val SALT_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private const val MAX_ARCHIVE_BYTES = 50 * 1024 * 1024
    private const val MAX_JSON_BYTES = 50 * 1024 * 1024
    private val random = java.security.SecureRandom()
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(snapshot: BackupSnapshot, passphrase: CharArray): ByteArray {
        require(passphrase.size >= 8) { "Backup password must contain at least 8 characters" }
        val plain = gzip(encodeSnapshot(snapshot))
        require(plain.size <= MAX_JSON_BYTES) { "Backup is too large" }

        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val header = header(salt, nonce)
        val encrypted = cipher(Cipher.ENCRYPT_MODE, passphrase, salt, nonce, header).doFinal(plain)
        return ByteArrayOutputStream(header.size + encrypted.size).use { output ->
            output.write(header)
            output.write(encrypted)
            output.toByteArray().also { require(it.size <= MAX_ARCHIVE_BYTES) { "Backup is too large" } }
        }
    }

    fun decode(archive: ByteArray, passphrase: CharArray): BackupSnapshot {
        require(passphrase.size >= 8) { "Backup password must contain at least 8 characters" }
        require(archive.size in HEADER_BYTES + TAG_BITS / 8..MAX_ARCHIVE_BYTES) { "Invalid backup size" }

        try {
            DataInputStream(ByteArrayInputStream(archive)).use { input ->
                val archiveMagic = ByteArray(magic.size).also(input::readFully)
                require(archiveMagic.contentEquals(magic)) { "Not a Venera Native backup" }
                val version = input.readUnsignedByte()
                require(version == FORMAT_VERSION) { "Unsupported backup format" }
                val iterations = input.readInt()
                require(iterations == KDF_ITERATIONS) { "Unsupported key derivation settings" }
                val salt = ByteArray(SALT_BYTES).also(input::readFully)
                val nonce = ByteArray(NONCE_BYTES).also(input::readFully)
                val header = header(salt, nonce)
                val encrypted = ByteArray(input.available()).also(input::readFully)
                val compressed = cipher(Cipher.DECRYPT_MODE, passphrase, salt, nonce, header).doFinal(encrypted)
                val plain = gunzipBounded(compressed)
                val snapshot = json.parseToJsonElement(plain.toString(Charsets.UTF_8)).jsonObject.toSnapshot()
                require(snapshot.schemaVersion == BackupSnapshot.CURRENT_BACKUP_SCHEMA) {
                    "Unsupported backup data version"
                }
                return snapshot
            }
        } catch (error: AEADBadTagException) {
            throw IllegalArgumentException("Backup password is incorrect or the file is damaged", error)
        } catch (error: IOException) {
            throw IllegalArgumentException("Backup file is damaged", error)
        } catch (error: kotlinx.serialization.SerializationException) {
            throw IllegalArgumentException("Backup data is invalid", error)
        }
    }

    private val HEADER_BYTES = magic.size + 1 + Int.SIZE_BYTES + SALT_BYTES + NONCE_BYTES

    private fun header(salt: ByteArray, nonce: ByteArray): ByteArray =
        ByteArrayOutputStream(HEADER_BYTES).use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write(magic)
                output.writeByte(FORMAT_VERSION)
                output.writeInt(KDF_ITERATIONS)
                output.write(salt)
                output.write(nonce)
            }
            bytes.toByteArray()
        }

    private fun cipher(mode: Int, passphrase: CharArray, salt: ByteArray, nonce: ByteArray, aad: ByteArray): Cipher {
        val spec = PBEKeySpec(passphrase, salt, KDF_ITERATIONS, 256)
        val keyBytes = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
        return try {
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(mode, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(TAG_BITS, nonce))
                updateAAD(aad)
            }
        } finally {
            keyBytes.fill(0)
        }
    }

    private fun gzip(value: ByteArray): ByteArray = ByteArrayOutputStream().use { bytes ->
        GZIPOutputStream(bytes).use { it.write(value) }
        bytes.toByteArray()
    }

    private fun gunzipBounded(value: ByteArray): ByteArray = GZIPInputStream(ByteArrayInputStream(value)).use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= MAX_JSON_BYTES) { "Backup data is too large" }
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }

    private fun encodeSnapshot(snapshot: BackupSnapshot): ByteArray = buildJsonObject {
        put("schemaVersion", snapshot.schemaVersion)
        put("createdAtEpochMillis", snapshot.createdAtEpochMillis)
        putJsonArray("categories") { snapshot.categories.sortedBy { it.name }.forEach { add(it.name) } }
        putJsonArray("favoriteFolders") {
            snapshot.favoriteFolders.forEach { row -> add(buildJsonObject {
                put("id", row.id); put("name", row.name); put("sortOrder", row.sortOrder); put("removable", row.removable)
            }) }
        }
        putJsonArray("favoriteEntries") {
            snapshot.favoriteEntries.forEach { row -> add(buildJsonObject {
                put("sourceId", row.sourceId); put("comicId", row.comicId); put("legacyFolderId", row.legacyFolderId)
                put("title", row.title); putNullable("subtitle", row.subtitle); putNullable("coverRef", row.coverRef)
                put("addedAtEpochMillis", row.addedAtEpochMillis); putNullable("lastReadAtEpochMillis", row.lastReadAtEpochMillis)
                putNullable("chapterCount", row.chapterCount); putNullable("latestChapterId", row.latestChapterId)
                put("hasUpdate", row.hasUpdate); putNullable("updatedAtEpochMillis", row.updatedAtEpochMillis)
            }) }
        }
        putJsonArray("favoriteMemberships") {
            snapshot.favoriteMemberships.forEach { row -> add(buildJsonObject {
                put("sourceId", row.sourceId); put("comicId", row.comicId); put("folderId", row.folderId)
            }) }
        }
        putJsonArray("readingHistory") {
            snapshot.readingHistory.forEach { row -> add(buildJsonObject {
                put("sourceId", row.sourceId); put("comicId", row.comicId); put("chapterId", row.chapterId)
                put("comicTitle", row.comicTitle); put("chapterTitle", row.chapterTitle); putNullable("coverUrl", row.coverUrl)
                put("pageIndex", row.pageIndex); put("pageCount", row.pageCount); put("updatedAtEpochMillis", row.updatedAtEpochMillis)
            }) }
        }
        putJsonArray("readingProgress") {
            snapshot.readingProgress.forEach { row -> add(buildJsonObject {
                put("sourceId", row.sourceId); put("comicId", row.comicId); put("chapterId", row.chapterId)
                put("pageIndex", row.pageIndex); put("updatedAtEpochMillis", row.updatedAtEpochMillis)
            }) }
        }
        putJsonArray("appPreferences") {
            snapshot.appPreferences.forEach { row -> add(buildJsonObject { put("key", row.key); put("value", row.value) }) }
        }
    }.toString().toByteArray(Charsets.UTF_8)

    private fun JsonObject.toSnapshot() = BackupSnapshot(
        schemaVersion = int("schemaVersion"),
        createdAtEpochMillis = long("createdAtEpochMillis"),
        categories = getValue("categories").jsonArray.map { BackupCategory.valueOf(it.jsonPrimitive.content) }.toSet(),
        favoriteFolders = array("favoriteFolders") { FavoriteFolderBackup(string("id"), string("name"), int("sortOrder"), boolean("removable")) },
        favoriteEntries = array("favoriteEntries") {
            FavoriteEntryBackup(string("sourceId"), string("comicId"), string("legacyFolderId"), string("title"), nullableString("subtitle"), nullableString("coverRef"), long("addedAtEpochMillis"), nullableLong("lastReadAtEpochMillis"), nullableInt("chapterCount"), nullableString("latestChapterId"), boolean("hasUpdate"), nullableLong("updatedAtEpochMillis"))
        },
        favoriteMemberships = array("favoriteMemberships") { FavoriteMembershipBackup(string("sourceId"), string("comicId"), string("folderId")) },
        readingHistory = array("readingHistory") {
            ReadingHistoryBackup(string("sourceId"), string("comicId"), string("chapterId"), string("comicTitle"), string("chapterTitle"), nullableString("coverUrl"), int("pageIndex"), int("pageCount"), long("updatedAtEpochMillis"))
        },
        readingProgress = array("readingProgress") { ReadingProgressBackup(string("sourceId"), string("comicId"), string("chapterId"), int("pageIndex"), long("updatedAtEpochMillis")) },
        appPreferences = array("appPreferences") { AppPreferenceBackup(string("key"), string("value")) },
    )

    private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content
    private fun JsonObject.int(name: String): Int = getValue(name).jsonPrimitive.int
    private fun JsonObject.long(name: String): Long = getValue(name).jsonPrimitive.long
    private fun JsonObject.boolean(name: String): Boolean = getValue(name).jsonPrimitive.boolean
    private fun JsonObject.nullableString(name: String): String? = getValue(name).jsonPrimitive.contentOrNull
    private fun JsonObject.nullableInt(name: String): Int? = getValue(name).jsonPrimitive.intOrNull
    private fun JsonObject.nullableLong(name: String): Long? = getValue(name).jsonPrimitive.longOrNull
    private fun <T> JsonObject.array(name: String, map: JsonObject.() -> T): List<T> =
        getValue(name).jsonArray.map { map(it.jsonObject) }

    private fun JsonObjectBuilder.putNullable(name: String, value: String?) = put(name, value?.let(::JsonPrimitive) ?: JsonNull)
    private fun JsonObjectBuilder.putNullable(name: String, value: Int?) = put(name, value?.let(::JsonPrimitive) ?: JsonNull)
    private fun JsonObjectBuilder.putNullable(name: String, value: Long?) = put(name, value?.let(::JsonPrimitive) ?: JsonNull)
}
