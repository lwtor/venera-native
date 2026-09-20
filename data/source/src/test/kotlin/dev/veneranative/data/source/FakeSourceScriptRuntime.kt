package dev.veneranative.data.source

import dev.veneranative.core.model.SourceId
import dev.veneranative.source.api.SourceCall
import dev.veneranative.source.api.SourceInstallResult
import dev.veneranative.source.api.SourcePackage
import dev.veneranative.source.api.SourceResult
import dev.veneranative.source.api.SourceRuntimeError
import dev.veneranative.source.api.SourceScriptRuntime

/**
 * Runtime double for install management tests.
 *
 * It separates "accepted" from "rejected" so tests can prove that a rejected package never reaches
 * storage, and it records the calls so enable/disable behaviour is observable without an engine.
 */
class FakeSourceScriptRuntime(
    val rejectedSourceIds: MutableSet<SourceId> = mutableSetOf(),
) : SourceScriptRuntime {

    val installedPackages = mutableListOf<SourcePackage>()
    val unloadedSourceIds = mutableListOf<SourceId>()
    var closed = false
        private set

    override fun isSupported(): Boolean = true

    override suspend fun install(source: SourcePackage): SourceInstallResult =
        if (source.sourceId in rejectedSourceIds) {
            SourceInstallResult.Failed(SourceRuntimeError.InvalidPackage("rejected by the runtime"))
        } else {
            installedPackages += source
            SourceInstallResult.Installed(sourceId = source.sourceId, version = source.version)
        }

    override suspend fun invoke(call: SourceCall): SourceResult =
        SourceResult.Failure(call.callId, SourceRuntimeError.InvalidCall("not used in these tests"))

    override suspend fun cancel(callId: String) = Unit

    override suspend fun unload(sourceId: SourceId) {
        unloadedSourceIds += sourceId
    }

    override suspend fun close() {
        closed = true
    }
}
