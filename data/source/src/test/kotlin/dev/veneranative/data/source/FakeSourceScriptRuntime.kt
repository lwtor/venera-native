package dev.veneranative.data.source

import dev.veneranative.core.model.SourceId
import dev.veneranative.source.api.SourceInstallResult
import dev.veneranative.source.api.SourcePackage
import dev.veneranative.source.api.SourceResult
import dev.veneranative.source.api.SourceRuntimeError
import dev.veneranative.source.api.SourceScriptRuntime
import dev.veneranative.source.api.SourceCall

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
    var nextInvokeResult: SourceResult = SourceResult.Failure("test", SourceRuntimeError.InvalidCall("not configured"))
    val calls = mutableListOf<SourceCall>()

    override fun isSupported(): Boolean = true

    override suspend fun install(source: SourcePackage): SourceInstallResult =
        if (source.sourceId in rejectedSourceIds) {
            SourceInstallResult.Failed(SourceRuntimeError.InvalidPackage("rejected by the runtime"))
        } else {
            installedPackages += source
            SourceInstallResult.Installed(sourceId = source.sourceId, version = source.version)
        }

    override suspend fun invoke(call: SourceCall): SourceResult {
        calls += call
        return when (val result = nextInvokeResult) {
            is SourceResult.Success -> result.copy(callId = call.callId)
            is SourceResult.Failure -> result.copy(callId = call.callId)
        }
    }

    override suspend fun cancel(callId: String) = Unit

    override suspend fun unload(sourceId: SourceId) {
        unloadedSourceIds += sourceId
    }

    override suspend fun close() {
        closed = true
    }
}
