package dev.veneranative.data.source

import dev.veneranative.core.model.InstalledSource
import dev.veneranative.core.model.InstalledSourceConfiguration
import dev.veneranative.core.model.SourceId
import dev.veneranative.source.api.SourceInstallResult
import dev.veneranative.source.api.SourceMetadataReader
import dev.veneranative.source.api.SourceMetadataResult
import dev.veneranative.source.api.SourcePackage
import dev.veneranative.source.api.SourceCall
import dev.veneranative.source.api.SourceResult
import dev.veneranative.source.api.SourceScriptRuntime
import java.security.MessageDigest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Install, enable, disable and remove comic sources.
 *
 * The repository owns the *stored* state; the runtime owns the *loaded* state. Both are updated in
 * an order that keeps a working install working: a package is only written to storage after the
 * runtime accepted it, so a failed install leaves the previous version exactly as it was.
 */
interface SourceRepository {
    suspend fun installed(): List<InstalledSource>

    suspend fun install(location: String): InstallOutcome

    /** Returns false when the source is not installed. */
    suspend fun setEnabled(sourceId: SourceId, enabled: Boolean): Boolean

    /** Returns false when the source was not installed. */
    suspend fun uninstall(sourceId: SourceId): Boolean

    suspend fun configuration(sourceId: SourceId): InstalledSourceConfiguration? = null

    /** [valueJson] is a JSON primitive matching the source-declared setting type. */
    suspend fun updateSetting(sourceId: SourceId, key: String, valueJson: String): Boolean = false

    suspend fun updateSettings(sourceId: SourceId, values: Map<String, String>): Boolean =
        values.all { (key, value) -> updateSetting(sourceId, key, value) }

    suspend fun invokeSettingCallback(sourceId: SourceId, key: String): Boolean = false

    suspend fun login(sourceId: SourceId, account: String, password: String): SourceLoginOutcome = SourceLoginOutcome.Unsupported

    suspend fun logout(sourceId: SourceId): SourceLoginOutcome = SourceLoginOutcome.Unsupported
}

sealed interface SourceLoginOutcome {
    data object Success : SourceLoginOutcome
    data object Unsupported : SourceLoginOutcome
    data object Failed : SourceLoginOutcome
}

sealed interface InstallOutcome {
    data class Success(val installed: InstalledSource) : InstallOutcome

    /**
     * [detail] is for logs and diagnostics only. Callers must render [error], never this string:
     * a lower layer's wording is not product copy.
     */
    data class Failure(
        val error: SourceInstallError,
        val detail: String? = null,
    ) : InstallOutcome
}

/** Why an install did not happen. Each case has a different thing the user can do about it. */
sealed interface SourceInstallError {
    /** The script could not be read from where the user pointed. */
    data object LocationUnreadable : SourceInstallError

    /** The script ran but did not declare usable metadata (missing key, name or version). */
    data object InvalidMetadata : SourceInstallError

    /** This device's JavaScript engine cannot run sources at all. */
    data object EngineUnavailable : SourceInstallError

    /** The runtime accepted the text but refused the package, for example on a broken script. */
    data object Rejected : SourceInstallError

    /** The package was accepted but could not be written to storage. */
    data object StorageFailed : SourceInstallError
}

class DefaultSourceRepository(
    private val store: SourcePackageStore,
    private val runtime: SourceScriptRuntime,
    private val metadataReader: SourceMetadataReader,
    private val fetcher: SourceScriptFetcher,
    private val privateSettings: SourcePrivateSettingsStore = InMemorySourcePrivateSettingsStore(),
    private val onSourceChanged: (SourceId) -> Unit = {},
) : SourceRepository {

    private val mutex = Mutex()
    private val loaded = mutableSetOf<SourceId>()

    override suspend fun installed(): List<InstalledSource> = mutex.withLock {
        store.list().also { entries ->
            for (entry in entries) {
                if (entry.enabled && entry.sourceId !in loaded) {
                    val stored = store.read(entry.sourceId) ?: continue
                    if (runtime.install(stored.toPackage()) is SourceInstallResult.Installed) {
                        loaded += entry.sourceId
                    }
                }
            }
        }
    }

    override suspend fun install(location: String): InstallOutcome = mutex.withLock { installLocked(location) }
    override suspend fun setEnabled(sourceId: SourceId, enabled: Boolean): Boolean =
        mutex.withLock { setEnabledLocked(sourceId, enabled) }
    override suspend fun uninstall(sourceId: SourceId): Boolean = mutex.withLock { uninstallLocked(sourceId) }

    override suspend fun configuration(sourceId: SourceId): InstalledSourceConfiguration? = mutex.withLock {
        configurationLocked(sourceId)
    }

    override suspend fun updateSetting(sourceId: SourceId, key: String, valueJson: String): Boolean = mutex.withLock {
        updateSettingsLocked(sourceId, mapOf(key to valueJson))
    }

    override suspend fun updateSettings(sourceId: SourceId, values: Map<String, String>): Boolean = mutex.withLock {
        updateSettingsLocked(sourceId, values)
    }

    private suspend fun updateSettingsLocked(sourceId: SourceId, values: Map<String, String>): Boolean {
        val stored = store.read(sourceId) ?: return false
        val metadata = (metadataReader.read(stored.script) as? SourceMetadataResult.Success)?.metadata ?: return false
        if (values.any { (key, value) ->
                val definition = metadata.settings.firstOrNull { it.key == key } ?: return@any true
                !validSettingValue(definition.type, value)
            }) return false
        val previous = privateSettings.read(sourceId)
        privateSettings.write(sourceId, previous + values)
        if (stored.installed.enabled) {
            if (runtime.install(stored.toPackage()) !is SourceInstallResult.Installed) {
                privateSettings.write(sourceId, previous)
                runtime.install(stored.toPackage())
                return false
            }
        }
        return true
    }

    override suspend fun invokeSettingCallback(sourceId: SourceId, key: String): Boolean = mutex.withLock {
        val stored = store.read(sourceId) ?: return@withLock false
        val metadata = (metadataReader.read(stored.script) as? SourceMetadataResult.Success)?.metadata ?: return@withLock false
        if (metadata.settings.none { it.key == key && it.type == dev.veneranative.core.model.SourceSettingDefinition.Type.CALLBACK }) {
            return@withLock false
        }
        invokeInternal(sourceId, "settings.$key.callback") is SourceResult.Success
    }

    override suspend fun login(sourceId: SourceId, account: String, password: String): SourceLoginOutcome = mutex.withLock {
        if (account.isBlank() || password.isBlank()) return@withLock SourceLoginOutcome.Failed
        val stored = store.read(sourceId) ?: return@withLock SourceLoginOutcome.Unsupported
        val metadata = (metadataReader.read(stored.script) as? SourceMetadataResult.Success)?.metadata
            ?: return@withLock SourceLoginOutcome.Unsupported
        if (!metadata.supportsPasswordLogin) return@withLock SourceLoginOutcome.Unsupported
        val arguments = org.json.JSONArray().put(account).put(password).toString()
        when (val result = invokeInternal(sourceId, "account.login", arguments)) {
            is SourceResult.Success -> SourceLoginOutcome.Success
            is SourceResult.Failure -> SourceLoginOutcome.Failed
        }
    }

    override suspend fun logout(sourceId: SourceId): SourceLoginOutcome = mutex.withLock {
        val stored = store.read(sourceId) ?: return@withLock SourceLoginOutcome.Unsupported
        val metadata = (metadataReader.read(stored.script) as? SourceMetadataResult.Success)?.metadata
            ?: return@withLock SourceLoginOutcome.Unsupported
        if (!metadata.supportsLogout) return@withLock SourceLoginOutcome.Unsupported
        when (val result = invokeInternal(sourceId, "account.logout")) {
            is SourceResult.Success -> SourceLoginOutcome.Success
            is SourceResult.Failure -> SourceLoginOutcome.Failed
        }
    }


    private suspend fun installLocked(location: String): InstallOutcome {
        val script = when (val fetched = fetcher.fetch(location)) {
            is FetchedScript.Failure ->
                return InstallOutcome.Failure(SourceInstallError.LocationUnreadable, fetched.reason)

            is FetchedScript.Success -> fetched.script
        }

        val metadata = when (val read = metadataReader.read(script)) {
            is SourceMetadataResult.Invalid ->
                return InstallOutcome.Failure(SourceInstallError.InvalidMetadata, read.reason)

            is SourceMetadataResult.EngineUnavailable ->
                return InstallOutcome.Failure(SourceInstallError.EngineUnavailable, read.reason)

            is SourceMetadataResult.Success -> read.metadata
        }

        val previous = store.read(metadata.sourceId)
        val packageToInstall = SourcePackage(
            sourceId = metadata.sourceId,
            version = metadata.version,
            script = script,
            // The runtime verifies this hash; computing it here produces its input, it does not
            // duplicate the check.
            sha256 = script.sha256Hex(),
            settingsJson = privateSettings.read(metadata.sourceId).toJsonObject(),
        )

        return when (val installed = runtime.install(packageToInstall)) {
            is SourceInstallResult.Failed ->
                // Storage is untouched, so the previous version keeps working.
                InstallOutcome.Failure(SourceInstallError.Rejected, installed.error.message)

            is SourceInstallResult.Installed -> {
                val entry = StoredSource(
                    installed = InstalledSource(
                        sourceId = metadata.sourceId,
                        name = metadata.name,
                        version = installed.version,
                        enabled = true,
                        origin = location,
                    ),
                    script = script,
                    sha256 = packageToInstall.sha256,
                )
                val stored = runCatching { store.write(entry) }.isSuccess
                if (stored) {
                    loaded += metadata.sourceId
                    InstallOutcome.Success(entry.installed)
                } else {
                    withContext(NonCancellable) { restore(previous, metadata.sourceId) }
                    InstallOutcome.Failure(SourceInstallError.StorageFailed)
                }
            }
        }
    }

    private suspend fun setEnabledLocked(sourceId: SourceId, enabled: Boolean): Boolean {
        val stored = store.read(sourceId) ?: return false
        if (stored.installed.enabled == enabled) return true

        if (enabled) {
            val installed = runtime.install(stored.toPackage())
            if (installed is SourceInstallResult.Failed) return false
        } else {
            runtime.unload(sourceId)
        }
        return try {
            store.setEnabled(sourceId, enabled).also {
                if (enabled) loaded += sourceId else loaded -= sourceId
                if (!enabled) onSourceChanged(sourceId)
            }
        } catch (failure: Exception) {
            withContext(NonCancellable) { restore(stored, sourceId) }
            throw failure
        }
    }

    private suspend fun uninstallLocked(sourceId: SourceId): Boolean {
        val removed = store.remove(sourceId)
        // Unload unconditionally: an unloaded source is not an error, and a stale isolate must not
        // survive an uninstall.
        runtime.unload(sourceId)
        privateSettings.clear(sourceId)
        loaded -= sourceId
        onSourceChanged(sourceId)
        return removed
    }

    /** Puts the runtime back to the state storage still describes after a failed write. */
    private suspend fun restore(previous: StoredSource?, sourceId: SourceId) {
        if (previous == null || !previous.installed.enabled) {
            runtime.unload(sourceId)
        } else {
            runtime.install(previous.toPackage())
        }
    }

    private suspend fun configurationLocked(sourceId: SourceId): InstalledSourceConfiguration? {
        val stored = store.read(sourceId) ?: return null
        val metadata = (metadataReader.read(stored.script) as? SourceMetadataResult.Success)?.metadata ?: return null
        val savedValues = privateSettings.read(sourceId)
        val loggedIn = (invokeInternal(sourceId, "__veneraIsLogged") as? SourceResult.Success)?.json == "true"
        return InstalledSourceConfiguration(
            source = stored.installed,
            settings = metadata.settings,
            settingValues = metadata.settings.associate { definition ->
                definition.key to (savedValues[definition.key] ?: definition.defaultJson ?: "null")
            },
            supportsPasswordLogin = metadata.supportsPasswordLogin,
            supportsLogout = metadata.supportsLogout,
            registerWebsite = metadata.registerWebsite,
            loggedIn = loggedIn,
        )
    }

    private suspend fun invokeInternal(sourceId: SourceId, member: String, argumentsJson: String = "[]"): SourceResult =
        runtime.invoke(
            SourceCall.InvokeFunction(
                callId = "source-management-${sourceCallSequence.incrementAndGet()}",
                sourceId = sourceId,
                functionName = member,
                argumentsJson = argumentsJson,
            ),
        )

    private fun validSettingValue(
        type: dev.veneranative.core.model.SourceSettingDefinition.Type,
        json: String,
    ): Boolean = runCatching {
        val value = org.json.JSONTokener(json).nextValue()
        when (type) {
            dev.veneranative.core.model.SourceSettingDefinition.Type.INPUT,
            dev.veneranative.core.model.SourceSettingDefinition.Type.SELECT -> value is String
            dev.veneranative.core.model.SourceSettingDefinition.Type.SWITCH -> value is Boolean
            dev.veneranative.core.model.SourceSettingDefinition.Type.CALLBACK -> false
        }
    }.getOrDefault(false)

    private fun Map<String, String>.toJsonObject(): String = buildString {
        append('{')
        entries.forEachIndexed { index, (key, value) ->
            if (index > 0) append(',')
            append(org.json.JSONObject.quote(key)).append(':').append(value)
        }
        append('}')
    }

    private fun StoredSource.toPackage() = SourcePackage(
        sourceId = installed.sourceId,
        version = installed.version,
        script = script,
        sha256 = sha256,
        settingsJson = privateSettings.read(installed.sourceId).toJsonObject(),
    )

    private companion object {
        val sourceCallSequence = java.util.concurrent.atomic.AtomicLong()
    }
}

private fun String.sha256Hex(): String =
    MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
