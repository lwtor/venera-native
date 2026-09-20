package dev.veneranative.source.engine

import android.content.Context
import androidx.javascriptengine.EvaluationFailedException
import androidx.javascriptengine.IsolateStartupParameters
import androidx.javascriptengine.IsolateTerminatedException
import androidx.javascriptengine.JavaScriptIsolate
import androidx.javascriptengine.JavaScriptSandbox
import androidx.javascriptengine.SandboxUnsupportedException
import com.google.common.util.concurrent.ListenableFuture
import dev.veneranative.core.model.SourceId
import dev.veneranative.source.api.SourceCall
import dev.veneranative.source.api.SourceHostApi
import dev.veneranative.source.api.SourceInstallResult
import dev.veneranative.source.api.SourcePackage
import dev.veneranative.source.api.SourceResult
import dev.veneranative.source.api.SourceRuntimeError
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * AndroidX JavaScriptEngine implementation of [dev.veneranative.source.api.SourceScriptRuntime].
 *
 * A single sandbox process owns one isolate per installed source. Calls for the same source are
 * serialized because an isolate has one global object and evaluates queued scripts in order.
 */
class AndroidJavaScriptRuntime(
    context: Context,
    private val installTimeoutMillis: Long = DEFAULT_INSTALL_TIMEOUT_MILLIS,
    private val maxEvaluationReturnSizeBytes: Int = DEFAULT_MAX_RESULT_BYTES,
    private val engineSupported: () -> Boolean = JavaScriptSandbox::isSupported,
    private val hostApi: SourceHostApi? = null,
) : dev.veneranative.source.api.SourceScriptRuntime {
    private val applicationContext = context.applicationContext
    private val lifecycleMutex = Mutex()
    private val sandboxMutex = Mutex()
    private val sessions = ConcurrentHashMap<SourceId, SourceSession>()
    private val activeCalls = ConcurrentHashMap<String, ActiveCall>()
    private val closed = AtomicBoolean(false)

    @Volatile
    private var sandbox: JavaScriptSandbox? = null

    override fun isSupported(): Boolean =
        !closed.get() && runCatching(engineSupported).getOrDefault(false)

    override suspend fun install(source: SourcePackage): SourceInstallResult {
        SourcePackageValidator.validate(source)?.let { reason ->
            return SourceInstallResult.Failed(SourceRuntimeError.InvalidPackage(reason))
        }
        if (closed.get()) {
            return SourceInstallResult.Failed(SourceRuntimeError.RuntimeClosed())
        }
        if (!isSupported()) {
            return SourceInstallResult.Failed(SourceRuntimeError.EngineUnavailable())
        }

        return lifecycleMutex.withLock {
            if (closed.get()) {
                return@withLock SourceInstallResult.Failed(SourceRuntimeError.RuntimeClosed())
            }

            val loadedIsolate =
                try {
                    withTimeout(installTimeoutMillis) { createLoadedIsolate(source) }
                } catch (_: TimeoutCancellationException) {
                    return@withLock SourceInstallResult.Failed(
                        SourceRuntimeError.Timeout(installTimeoutMillis),
                    )
                } catch (failure: RuntimeFailure) {
                    return@withLock SourceInstallResult.Failed(failure.error)
                } catch (failure: EvaluationFailedException) {
                    return@withLock SourceInstallResult.Failed(
                        SourceRuntimeError.ScriptSyntax(messageFor("Source script failed to load", failure)),
                    )
                } catch (failure: Throwable) {
                    return@withLock SourceInstallResult.Failed(mapEngineFailure(failure))
                }

            val replacement = SourceSession(source, loadedIsolate)
            sessions.put(source.sourceId, replacement)?.invalidate()
            SourceInstallResult.Installed(source.sourceId, source.version)
        }
    }

    override suspend fun invoke(call: SourceCall): SourceResult {
        if (closed.get()) return call.failure(SourceRuntimeError.RuntimeClosed())

        val typedCall = call as? SourceCall.InvokeFunction
            ?: return call.failure(SourceRuntimeError.InvalidCall("Unsupported source call."))
        validateCall(typedCall)?.let { return typedCall.failure(it) }

        val session =
            sessions[typedCall.sourceId]
                ?: return typedCall.failure(SourceRuntimeError.SourceNotLoaded(typedCall.sourceId))

        return session.invocationMutex.withLock {
            if (closed.get()) return@withLock typedCall.failure(SourceRuntimeError.RuntimeClosed())
            if (sessions[typedCall.sourceId] !== session) {
                return@withLock typedCall.failure(
                    SourceRuntimeError.SourceNotLoaded(typedCall.sourceId),
                )
            }

            val activeCall = ActiveCall(session)
            if (activeCalls.putIfAbsent(typedCall.callId, activeCall) != null) {
                return@withLock typedCall.failure(
                    SourceRuntimeError.InvalidCall("Call ID is already active."),
                )
            }

            try {
                val isolate =
                    (session.loadedIsolate ?: createLoadedIsolate(session.source).also {
                        session.loadedIsolate = it
                    }).isolate
                if (activeCall.cancelled.get()) {
                    session.invalidate()
                    return@withLock typedCall.failure(SourceRuntimeError.Cancelled())
                }

                val invocationScript =
                    SourceInvocationScript.build(
                        member = typedCall.functionName,
                        argumentsJson = typedCall.argumentsJson,
                        invocationId = typedCall.callId,
                    )
                val future = isolate.evaluateJavaScriptAsync(invocationScript)
                activeCall.attach(future)

                val envelope =
                    try {
                        withTimeout(typedCall.timeoutMillis) { future.awaitCancellable() }
                    } catch (_: TimeoutCancellationException) {
                        activeCall.terminate()
                        return@withLock typedCall.failure(
                            SourceRuntimeError.Timeout(typedCall.timeoutMillis),
                        )
                    } catch (failure: CancellationException) {
                        if (activeCall.cancelled.get()) {
                            activeCall.terminate()
                            return@withLock typedCall.failure(SourceRuntimeError.Cancelled())
                        }
                        activeCall.terminate()
                        throw failure
                    } catch (failure: Throwable) {
                        if (activeCall.cancelled.get()) {
                            activeCall.terminate()
                            return@withLock typedCall.failure(SourceRuntimeError.Cancelled())
                        }
                        if (failure is IsolateTerminatedException) session.invalidate()
                        return@withLock typedCall.failure(mapInvocationFailure(failure))
                    }

                SourceResult.Success(typedCall.callId, SourceResultEnvelope.extract(envelope))
            } catch (failure: RuntimeFailure) {
                typedCall.failure(failure.error)
            } catch (failure: JSONException) {
                typedCall.failure(
                    SourceRuntimeError.ScriptExecution(
                        messageFor("Source returned invalid JSON", failure),
                    ),
                )
            } catch (failure: Throwable) {
                typedCall.failure(mapInvocationFailure(failure))
            } finally {
                activeCalls.remove(typedCall.callId, activeCall)
            }
        }
    }

    override suspend fun cancel(callId: String) {
        activeCalls[callId]?.cancel()
    }

    override suspend fun unload(sourceId: SourceId) {
        lifecycleMutex.withLock {
            sessions.remove(sourceId)?.invalidate()
        }
    }

    override suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        lifecycleMutex.withLock {
            activeCalls.values.forEach(ActiveCall::cancel)
            activeCalls.clear()
            sessions.values.forEach(SourceSession::invalidate)
            sessions.clear()
            sandboxMutex.withLock {
                sandbox?.close()
                sandbox = null
            }
        }
    }

    private suspend fun createLoadedIsolate(source: SourcePackage): LoadedIsolate {
        val currentSandbox = getOrCreateSandbox()
        if (!currentSandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_PROMISE_RETURN)) {
            throw RuntimeFailure(
                SourceRuntimeError.EngineUnavailable(
                    "JavaScript engine does not support Promise return values.",
                ),
            )
        }
        if (!currentSandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_ISOLATE_TERMINATION)) {
            throw RuntimeFailure(
                SourceRuntimeError.EngineUnavailable(
                    "JavaScript engine does not support reliable isolate termination.",
                ),
            )
        }
        if (
            hostApi != null &&
            !currentSandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_MESSAGE_PORTS)
        ) {
            throw RuntimeFailure(
                SourceRuntimeError.EngineUnavailable(
                    "JavaScript engine does not support MessagePort Host APIs.",
                ),
            )
        }

        val parameters = IsolateStartupParameters()
        if (
            currentSandbox.isFeatureSupported(
                JavaScriptSandbox.JS_FEATURE_EVALUATE_WITHOUT_TRANSACTION_LIMIT,
            )
        ) {
            parameters.apply {
                setMaxEvaluationReturnSizeBytes(maxEvaluationReturnSizeBytes)
            }
        }
        val isolate =
            try {
                currentSandbox.createIsolate(parameters)
            } catch (failure: Throwable) {
                throw RuntimeFailure(mapEngineFailure(failure))
            }

        var hostBridge: SourceHostBridge? = null
        try {
            if (hostApi != null) {
                hostBridge = SourceHostBridge(source.sourceId, currentSandbox, isolate, hostApi)
                hostBridge.initialize()
            }
            val loadScript = source.script + "\n;\"" + SOURCE_LOADED_SENTINEL + "\";"
            val result = isolate.evaluateJavaScriptAsync(loadScript).awaitCancellable()
            if (result != SOURCE_LOADED_SENTINEL) {
                throw RuntimeFailure(
                    SourceRuntimeError.InvalidPackage(
                        "Source script did not complete initialization.",
                    ),
                )
            }
            return LoadedIsolate(isolate, hostBridge)
        } catch (failure: Throwable) {
            hostBridge?.close()
            isolate.close()
            throw failure
        }
    }

    private suspend fun getOrCreateSandbox(): JavaScriptSandbox {
        sandbox?.let { return it }
        return sandboxMutex.withLock {
            sandbox?.let { return@withLock it }
            if (closed.get()) throw RuntimeFailure(SourceRuntimeError.RuntimeClosed())
            if (!isSupported()) throw RuntimeFailure(SourceRuntimeError.EngineUnavailable())

            val created =
                try {
                    JavaScriptSandbox.createConnectedInstanceAsync(applicationContext)
                        .awaitCancellable()
                } catch (_: SandboxUnsupportedException) {
                    throw RuntimeFailure(SourceRuntimeError.EngineUnavailable())
                } catch (failure: Throwable) {
                    throw RuntimeFailure(mapEngineFailure(failure))
                }
            sandbox = created
            created
        }
    }

    private fun validateCall(call: SourceCall.InvokeFunction): SourceRuntimeError.InvalidCall? {
        if (call.callId.isBlank()) {
            return SourceRuntimeError.InvalidCall("Call ID must not be blank.")
        }
        if (!SourceInvocationScript.validateMember(call.functionName)) {
            return SourceRuntimeError.InvalidCall("Function name is not a valid member path.")
        }
        if (call.timeoutMillis !in 1..MAX_CALL_TIMEOUT_MILLIS) {
            return SourceRuntimeError.InvalidCall(
                "Timeout must be between 1 and $MAX_CALL_TIMEOUT_MILLIS milliseconds.",
            )
        }
        try {
            JSONArray(call.argumentsJson)
        } catch (_: JSONException) {
            return SourceRuntimeError.InvalidCall("Function arguments must be a JSON array.")
        }
        return null
    }

    private fun mapInvocationFailure(failure: Throwable): SourceRuntimeError =
        when (failure) {
            is EvaluationFailedException ->
                SourceRuntimeError.ScriptExecution(messageFor("Source function failed", failure))
            is IsolateTerminatedException ->
                SourceRuntimeError.EngineTerminated(messageFor("Source isolate terminated", failure))
            is RuntimeFailure -> failure.error
            else -> mapEngineFailure(failure)
        }

    private fun mapEngineFailure(failure: Throwable): SourceRuntimeError =
        when (failure) {
            is SandboxUnsupportedException -> SourceRuntimeError.EngineUnavailable()
            is IsolateTerminatedException ->
                SourceRuntimeError.EngineTerminated(messageFor("JavaScript engine terminated", failure))
            else -> SourceRuntimeError.Internal(messageFor("JavaScript engine failure", failure))
        }

    private fun SourceCall.failure(error: SourceRuntimeError): SourceResult.Failure =
        SourceResult.Failure(callId, error)

    private fun messageFor(prefix: String, failure: Throwable): String {
        val detail = failure.message?.trim()?.take(MAX_ERROR_DETAIL_LENGTH)
        return if (detail.isNullOrEmpty()) prefix else "$prefix: $detail"
    }

    private class RuntimeFailure(val error: SourceRuntimeError) : Exception(error.message)

    private class SourceSession(
        val source: SourcePackage,
        @Volatile var loadedIsolate: LoadedIsolate?,
    ) {
        val invocationMutex = Mutex()

        fun invalidate() {
            synchronized(this) {
                loadedIsolate?.close()
                loadedIsolate = null
            }
        }
    }

    private class LoadedIsolate(
        val isolate: JavaScriptIsolate,
        val hostBridge: SourceHostBridge?,
    ) : AutoCloseable {
        override fun close() {
            hostBridge?.close()
            isolate.close()
        }
    }

    private class ActiveCall(private val session: SourceSession) {
        val cancelled = AtomicBoolean(false)
        private val future = AtomicReference<ListenableFuture<String>?>(null)

        fun attach(value: ListenableFuture<String>) {
            check(future.compareAndSet(null, value))
            if (cancelled.get()) {
                value.cancel(true)
                session.invalidate()
            }
        }

        fun cancel() {
            cancelled.set(true)
            terminate()
        }

        fun terminate() {
            future.get()?.cancel(true)
            session.invalidate()
        }
    }

    private companion object {
        const val DEFAULT_INSTALL_TIMEOUT_MILLIS = 5_000L
        const val DEFAULT_MAX_RESULT_BYTES = 1_048_576
        const val MAX_CALL_TIMEOUT_MILLIS = 120_000L
        const val MAX_ERROR_DETAIL_LENGTH = 512
        const val SOURCE_LOADED_SENTINEL = "__VENERA_SOURCE_LOADED__"
    }
}
