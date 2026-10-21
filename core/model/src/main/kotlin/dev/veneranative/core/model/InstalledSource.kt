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
    val settings: List<SourceSettingDefinition> = emptyList(),
    val supportsPasswordLogin: Boolean = false,
    val supportsLogout: Boolean = false,
    val registerWebsite: String? = null,
) {
    init {
        require(name.isNotBlank()) { "name must not be blank" }
        require(version.isNotBlank()) { "version must not be blank" }
    }
}

/** Source-declared setting that the native source settings UI can render without executing JS. */
data class SourceSettingDefinition(
    val key: String,
    val title: String,
    val type: Type,
    /** JSON primitive encoded as text; null means the source did not declare a default. */
    val defaultJson: String? = null,
    val options: List<SourceSettingOption> = emptyList(),
    val actionLabel: String? = null,
) {
    enum class Type { INPUT, SELECT, SWITCH, CALLBACK }
}

data class SourceSettingOption(val value: String, val text: String)

data class InstalledSourceConfiguration(
    val source: InstalledSource,
    val settings: List<SourceSettingDefinition>,
    /** JSON primitive values keyed by setting id; never contains account passwords. */
    val settingValues: Map<String, String>,
    val supportsPasswordLogin: Boolean,
    val supportsLogout: Boolean,
    val registerWebsite: String?,
    val loggedIn: Boolean,
)

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
