package dev.veneranative.data.source

import dev.veneranative.core.model.InstalledSource
import dev.veneranative.core.model.SourceId
import java.io.File
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
class SourcePackageStore(private val rootDir: File) {

    private val indexFile = File(rootDir, INDEX_FILE_NAME)

    fun list(): List<InstalledSource> = synchronized(LOCK) { readIndex().map { it.toInstalledSource() } }

    fun read(sourceId: SourceId): StoredSource? = synchronized(LOCK) {
        val entry = readIndex().firstOrNull { it.sourceId == sourceId.value } ?: return@synchronized null
        val script = scriptFile(sourceId).takeIf(File::isFile)?.readText() ?: return@synchronized null
        StoredSource(installed = entry.toInstalledSource(), script = script, sha256 = entry.sha256)
    }

    fun write(source: StoredSource) {
        synchronized(LOCK) {
            val directory = directoryFor(source.installed.sourceId)
            directory.mkdirs()
            File(directory, SCRIPT_FILE_NAME).writeText(source.script)
            val entries = readIndex().filterNot { it.sourceId == source.installed.sourceId.value } +
                ManifestEntry.of(source)
            writeIndex(entries)
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
        val parsed = runCatching { Json.parseToJsonElement(indexFile.readText()) }.getOrNull() ?: return emptyList()
        val array = (parsed as? JsonObject)?.get(SOURCES_KEY) as? JsonArray ?: return emptyList()
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
        indexFile.writeText(payload.toString())
    }

    private fun directoryFor(sourceId: SourceId): File = File(rootDir, encodeDirectoryName(sourceId))

    private fun scriptFile(sourceId: SourceId): File = File(directoryFor(sourceId), SCRIPT_FILE_NAME)

    private fun encodeDirectoryName(sourceId: SourceId): String =
        sourceId.value.toByteArray(Charsets.UTF_8).joinToString(separator = "") { byte -> "%02x".format(byte) }

    private data class ManifestEntry(
        val sourceId: String,
        val name: String,
        val version: String,
        val enabled: Boolean,
        val origin: String,
        val sha256: String,
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
        }

        companion object {
            fun of(source: StoredSource) = ManifestEntry(
                sourceId = source.installed.sourceId.value,
                name = source.installed.name,
                version = source.installed.version,
                enabled = source.installed.enabled,
                origin = source.installed.origin,
                sha256 = source.sha256,
            )

            /** A malformed entry is dropped: one bad record must not hide the whole list. */
            fun from(element: JsonObject): ManifestEntry? {
                val sourceId = element["sourceId"]?.jsonPrimitive?.contentOrNull ?: return null
                val name = element["name"]?.jsonPrimitive?.contentOrNull ?: return null
                val version = element["version"]?.jsonPrimitive?.contentOrNull ?: return null
                return ManifestEntry(
                    sourceId = sourceId,
                    name = name,
                    version = version,
                    enabled = element["enabled"]?.jsonPrimitive?.booleanOrNull ?: true,
                    origin = element["origin"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    sha256 = element["sha256"]?.jsonPrimitive?.contentOrNull.orEmpty(),
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
