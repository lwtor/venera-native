package dev.veneranative.data.source

import dev.veneranative.core.model.InstalledSource
import dev.veneranative.core.model.SourceId
import dev.veneranative.source.api.SourceInstallResult
import dev.veneranative.source.api.SourceMetadataReader
import dev.veneranative.source.api.SourceMetadataResult
import dev.veneranative.source.api.SourcePackage
import dev.veneranative.source.api.SourceScriptRuntime
import java.security.MessageDigest

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
}

sealed interface InstallOutcome {
    data class Success(val installed: InstalledSource) : InstallOutcome

    data class Failure(val reason: String) : InstallOutcome
}

class DefaultSourceRepository(
    private val store: SourcePackageStore,
    private val runtime: SourceScriptRuntime,
    private val metadataReader: SourceMetadataReader,
    private val fetcher: SourceScriptFetcher,
) : SourceRepository {

    override suspend fun installed(): List<InstalledSource> = store.list()

    override suspend fun install(location: String): InstallOutcome {
        val script = when (val fetched = fetcher.fetch(location)) {
            is FetchedScript.Failure -> return InstallOutcome.Failure(fetched.reason)
            is FetchedScript.Success -> fetched.script
        }

        val metadata = when (val read = metadataReader.read(script)) {
            is SourceMetadataResult.Invalid -> return InstallOutcome.Failure(read.reason)
            is SourceMetadataResult.EngineUnavailable -> return InstallOutcome.Failure(read.reason)
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
        )

        return when (val installed = runtime.install(packageToInstall)) {
            is SourceInstallResult.Failed ->
                // Storage is untouched, so the previous version keeps working.
                InstallOutcome.Failure(installed.error.message)

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
                    InstallOutcome.Success(entry.installed)
                } else {
                    restore(previous, metadata.sourceId)
                    InstallOutcome.Failure("The source could not be stored on this device.")
                }
            }
        }
    }

    override suspend fun setEnabled(sourceId: SourceId, enabled: Boolean): Boolean {
        val stored = store.read(sourceId) ?: return false
        if (stored.installed.enabled == enabled) return true

        if (enabled) {
            val installed = runtime.install(stored.toPackage())
            if (installed is SourceInstallResult.Failed) return false
        } else {
            runtime.unload(sourceId)
        }
        return store.setEnabled(sourceId, enabled)
    }

    override suspend fun uninstall(sourceId: SourceId): Boolean {
        val removed = store.remove(sourceId)
        // Unload unconditionally: an unloaded source is not an error, and a stale isolate must not
        // survive an uninstall.
        runtime.unload(sourceId)
        return removed
    }

    /** Puts the runtime back to the state storage still describes after a failed write. */
    private suspend fun restore(previous: StoredSource?, sourceId: SourceId) {
        if (previous == null) {
            runtime.unload(sourceId)
        } else {
            runtime.install(previous.toPackage())
        }
    }

    private fun StoredSource.toPackage() = SourcePackage(
        sourceId = installed.sourceId,
        version = installed.version,
        script = script,
        sha256 = sha256,
    )
}

private fun String.sha256Hex(): String =
    MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
