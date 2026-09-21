package dev.veneranative.data.source

import dev.veneranative.core.model.InstalledSource
import dev.veneranative.core.model.SourceId
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** An installed package as it is stored on disk. */
data class StoredSource(
    val installed: InstalledSource,
    val script: String,
    val sha256: String,
)

/**
 * File-backed storage for installed source packages.
 *
 * Layout: one directory per source holding the script, plus a JSON index with the metadata the list
 * screen needs — so showing the source list never has to read or execute a script again.
 *
 * Directory names are derived from a hex encoding of the source id rather than from the id text: a
 * script chooses its own id, and a hostile one must not be able to escape the storage root.
 */
class SourcePackageStore(
    private val rootDir: File,
    private val beforeIndexCommit: () -> Unit = {},
) {

    private val indexFile = File(rootDir, INDEX_FILE_NAME)

    fun list(): List<InstalledSource> = synchronized(LOCK) { readIndex().map { it.toInstalledSource() } }

    fun read(sourceId: SourceId): StoredSource? = synchronized(LOCK) {
        val entry = readIndex().firstOrNull { it.sourceId == sourceId.value } ?: return@synchronized null
        val script = File(directoryFor(sourceId), entry.scriptFile).takeIf(File::isFile)?.readText() ?: return@synchronized null
        StoredSource(installed = entry.toInstalledSource(), script = script, sha256 = entry.sha256)
    }

    fun write(source: StoredSource) {
        synchronized(LOCK) {
            val directory = directoryFor(source.installed.sourceId)
            directory.mkdirs()
            val entries = readIndex()
            val script = File(directory, "${UUID.randomUUID()}.js")
            try {
                FileOutputStream(script).use { output ->
                    output.write(source.script.toByteArray(Charsets.UTF_8))
                    output.fd.sync()
                }
                writeIndex(entries.filterNot { it.sourceId == source.installed.sourceId.value } +
                    ManifestEntry.of(source, script.name))
            } catch (failure: Exception) {
                script.delete()
                throw failure
            }
            // The atomic index replacement is the commit point. Cleanup cannot invalidate it.
            directory.listFiles()?.filter { it != script }?.forEach { it.delete() }
        }
    }

    fun setEnabled(sourceId: SourceId, enabled: Boolean): Boolean = synchronized(LOCK) {
        val entries = readIndex()
        val updated = entries.map { entry ->
            if (entry.sourceId == sourceId.value) entry.copy(enabled = enabled) else entry
        }
        if (updated == entries) return@synchronized false
        writeIndex(updated)
        true
    }

    /** Removes the entry and its directory; returns false when the source was not installed. */
    fun remove(sourceId: SourceId): Boolean = synchronized(LOCK) {
        val entries = readIndex()
        if (entries.none { it.sourceId == sourceId.value }) return@synchronized false
        writeIndex(entries.filterNot { it.sourceId == sourceId.value })
        directoryFor(sourceId).deleteRecursively()
        true
    }

    private fun readIndex(): List<ManifestEntry> {
        if (!indexFile.isFile) return emptyList()
        val parsed = try { Json.parseToJsonElement(indexFile.readText()) }
        catch (failure: Exception) { throw IOException("Invalid source index", failure) }
        val array = (parsed as? JsonObject)?.get(SOURCES_KEY) as? JsonArray ?: throw IOException("Invalid source index")
        return array.mapNotNull { element -> ManifestEntry.from(element as? JsonObject ?: return@mapNotNull null) }
    }

    private fun writeIndex(entries: List<ManifestEntry>) {
        rootDir.mkdirs()
        val payload = buildJsonObject {
            put(
                SOURCES_KEY,
                buildJsonArray { entries.forEach { add(it.toJson()) } },
            )
        }
        val temporary = File.createTempFile("index-", ".tmp", rootDir)
        try {
            FileOutputStream(temporary).use { output ->
                output.write(payload.toString().toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            beforeIndexCommit()
            Files.move(temporary.toPath(), indexFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temporary.delete()
        }
    }

    private fun directoryFor(sourceId: SourceId): File = File(rootDir, encodeDirectoryName(sourceId))

    private fun encodeDirectoryName(sourceId: SourceId): String =
        sourceId.value.toByteArray(Charsets.UTF_8).joinToString(separator = "") { byte -> "%02x".format(byte) }

    private data class ManifestEntry(
        val sourceId: String,
        val name: String,
        val version: String,
        val enabled: Boolean,
        val origin: String,
        val sha256: String,
        val scriptFile: String = SCRIPT_FILE_NAME,
    ) {
        fun toInstalledSource() = InstalledSource(
            sourceId = SourceId(sourceId),
            name = name,
            version = version,
            enabled = enabled,
            origin = origin,
        )

        fun toJson(): JsonObject = buildJsonObject {
            put("sourceId", JsonPrimitive(sourceId))
            put("name", JsonPrimitive(name))
            put("version", JsonPrimitive(version))
            put("enabled", JsonPrimitive(enabled))
            put("origin", JsonPrimitive(origin))
            put("sha256", JsonPrimitive(sha256))
            put("scriptFile", JsonPrimitive(scriptFile))
        }

        companion object {
            fun of(source: StoredSource, scriptFile: String) = ManifestEntry(
                sourceId = source.installed.sourceId.value,
                name = source.installed.name,
                version = source.installed.version,
                enabled = source.installed.enabled,
                origin = source.installed.origin,
                sha256 = source.sha256,
                scriptFile = scriptFile,
            )

            /** A malformed entry is dropped: one bad record must not hide the whole list. */
            fun from(element: JsonObject): ManifestEntry? {
                fun text(key: String) = (element[key] as? JsonPrimitive)?.contentOrNull
                val sourceId = text("sourceId")?.takeIf { it.isNotBlank() } ?: return null
                val name = text("name")?.takeIf { it.isNotBlank() } ?: return null
                val version = text("version")?.takeIf { it.isNotBlank() } ?: return null
                val scriptFile = text("scriptFile") ?: SCRIPT_FILE_NAME
                if (!scriptFile.matches(Regex("[a-zA-Z0-9-]+\\.js"))) return null
                return ManifestEntry(
                    sourceId = sourceId,
                    name = name,
                    version = version,
                    enabled = (element["enabled"] as? JsonPrimitive)?.booleanOrNull ?: true,
                    origin = text("origin").orEmpty(),
                    sha256 = text("sha256").orEmpty(),
                    scriptFile = scriptFile,
                )
            }
        }
    }

    private companion object {
        val LOCK = Any()
        const val INDEX_FILE_NAME = "index.json"
        const val SCRIPT_FILE_NAME = "source.js"
        const val SOURCES_KEY = "sources"
    }
}
