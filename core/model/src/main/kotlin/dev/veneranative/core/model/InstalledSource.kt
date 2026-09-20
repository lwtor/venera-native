package dev.veneranative.core.model

/**
 * What a source script declares about itself.
 *
 * Upstream requires `name`, `key` and `version`, and optionally `minAppVersion`; the values come
 * from executing the script, so this is read by the engine and never parsed from text.
 */
data class SourceMetadata(
    val sourceId: SourceId,
    val name: String,
    val version: String,
    val minAppVersion: String? = null,
) {
    init {
        require(name.isNotBlank()) { "name must not be blank" }
        require(version.isNotBlank()) { "version must not be blank" }
    }
}

/**
 * A source that is installed on this device.
 *
 * [sourceId] is the script's own `key`. Two packages that declare the same key are therefore the
 * same source: a second install replaces the first instead of coexisting, which is also what keeps
 * `ComicKey` unambiguous.
 */
data class InstalledSource(
    val sourceId: SourceId,
    val name: String,
    val version: String,
    val enabled: Boolean,
    /** Where the package came from, kept so the UI can show it and a reinstall can reuse it. */
    val origin: String,
)
