package dev.veneranative.source.engine

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.QuickJsException
import dev.veneranative.core.model.SourceId
import dev.veneranative.source.api.SourceCall
import dev.veneranative.source.api.SourceHostApi
import dev.veneranative.source.api.SourceInstallResult
import dev.veneranative.source.api.SourcePackage
import dev.veneranative.source.api.SourceResult
import dev.veneranative.source.api.SourceRuntimeError
import dev.veneranative.source.api.SourceScriptRuntime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.text.Charsets.UTF_8
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONException

/**
 * Owned-engine implementation of [SourceScriptRuntime] (ADR-0008).
 *
 * One engine instance per installed source: separate globals, separate memory, and unloading is a
 * close instead of a restart. Calls for the same source are serialized because a source has a single
 * global object; calls for different sources run in parallel.
 *
 * **Interruption is best effort, and that is a measured property of the pinned binding**
 * (ADR-0008 §10): a script suspended on a host call is cancelled cleanly, but a script spinning
 * inside the engine cannot be stopped from Kotlin. Two consequences shape this class:
 *
 * - an evaluation runs in the session's own scope, never as a child of the caller. A child of the
 *   caller would make `coroutineScope` wait for an uninterruptible script, turning a timeout into a
 *   hang, and an `async` child failure would bypass the error mapping below;
 * - after a timeout or a cancellation the engine is treated as dirty and rebuilt on the next call,
 *   the same way the WebView runtime drops an isolate it terminated.
 */
class QuickJsRuntime(
    private val hostApi: SourceHostApi? = null,
    private val installTimeoutMillis: Long = DEFAULT_INSTALL_TIMEOUT_MILLIS,
    private val maxResultBytes: Int = DEFAULT_MAX_RESULT_BYTES,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val appLocale: String = DEFAULT_APP_LOCALE,
    private val appVersion: String = DEFAULT_APP_VERSION,
    private val logSink: (String, String) -> Unit = QuickJsHostBridge.DEFAULT_LOG_SINK,
) : SourceScriptRuntime {

    private val lifecycleMutex = Mutex()
    private val sessions = ConcurrentHashMap<SourceId, LoadedSource>()
    private val activeCalls = ConcurrentHashMap<String, ActiveCall>()
    private val closed = AtomicBoolean(false)

    /** Creating an instance is the only honest way to know the native library loaded. */
    private val engineAvailable: Boolean by lazy {
        runCatching { QuickJs.create(dispatcher).close() }.isSuccess
    }

    override fun isSupported(): Boolean = !closed.get() && engineAvailable

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

            val session = LoadedSource(source, dispatcher)
            try {
                withTimeout(installTimeoutMillis) { session.load() }
            } catch (_: TimeoutCancellationException) {
                session.discard()
                return@withLock SourceInstallResult.Failed(
                    SourceRuntimeError.Timeout(installTimeoutMillis),
                )
            } catch (failure: RuntimeFailure) {
                session.discard()
                return@withLock SourceInstallResult.Failed(failure.error)
            } catch (failure: Throwable) {
                session.discard()
                return@withLock SourceInstallResult.Failed(mapLoadFailure(failure))
            }

            sessions.put(source.sourceId, session)?.discard()
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

            val activeCall = ActiveCall()
            if (activeCalls.putIfAbsent(typedCall.callId, activeCall) != null) {
                return@withLock typedCall.failure(
                    SourceRuntimeError.InvalidCall("Call ID is already active."),
                )
            }

            try {
                val engine =
                    try {
                        session.engine()
                    } catch (failure: RuntimeFailure) {
                        return@withLock typedCall.failure(failure.error)
                    } catch (failure: Throwable) {
                        return@withLock typedCall.failure(mapLoadFailure(failure))
                    }

                val invocationScript =
                    SourceInvocationScript.buildAwaitable(
                        functionName = typedCall.functionName,
                        argumentsJson = typedCall.argumentsJson,
                        invocationId = typedCall.callId,
                    )

                val evaluation = session.evaluate(engine, invocationScript)
                activeCall.attach(evaluation)

                val envelope =
                    try {
                        withTimeout(typedCall.timeoutMillis) { evaluation.await() }
                    } catch (_: TimeoutCancellationException) {
                        session.discardEngine()
                        return@withLock typedCall.failure(
                            SourceRuntimeError.Timeout(typedCall.timeoutMillis),
                        )
                    } catch (cancellation: CancellationException) {
                        session.discardEngine()
                        if (activeCall.cancelled.get()) {
                            return@withLock typedCall.failure(SourceRuntimeError.Cancelled())
                        }
                        throw cancellation
                    } catch (failure: Throwable) {
                        return@withLock typedCall.failure(mapInvocationFailure(failure))
                    }

                if (activeCall.cancelled.get()) {
                    session.discardEngine()
                    return@withLock typedCall.failure(SourceRuntimeError.Cancelled())
                }
                if (envelope.toByteArray(UTF_8).size > maxResultBytes) {
                    return@withLock typedCall.failure(
                        SourceRuntimeError.ScriptExecution(
                            "Source result exceeded the allowed size.",
                        ),
                    )
                }

                try {
                    SourceResult.Success(typedCall.callId, SourceResultEnvelope.extract(envelope))
                } catch (failure: JSONException) {
                    typedCall.failure(
                        SourceRuntimeError.ScriptExecution(
                            messageFor("Source returned invalid JSON", failure),
                        ),
                    )
                }
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
            sessions.remove(sourceId)?.discard()
        }
    }

    override suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        lifecycleMutex.withLock {
            activeCalls.values.forEach(ActiveCall::cancel)
            activeCalls.clear()
            sessions.values.forEach(LoadedSource::discard)
            sessions.clear()
        }
    }

    private fun mapInvocationFailure(failure: Throwable): SourceRuntimeError =
        when (failure) {
            is RuntimeFailure -> failure.error
            is QuickJsException -> scriptError(failure, "Source function failed")
            else -> SourceRuntimeError.Internal(messageFor("Source call failed", failure))
        }

    private fun mapLoadFailure(failure: Throwable): SourceRuntimeError =
        when (failure) {
            is RuntimeFailure -> failure.error
            is QuickJsException ->
                if (isSyntaxError(failure)) {
                    SourceRuntimeError.ScriptSyntax(messageFor("Source script is not valid", failure))
                } else {
                    SourceRuntimeError.InvalidPackage(
                        messageFor("Source script failed to load", failure),
                    )
                }

            else -> SourceRuntimeError.Internal(messageFor("Source load failed", failure))
        }

    private fun scriptError(failure: QuickJsException, prefix: String): SourceRuntimeError =
        if (isSyntaxError(failure)) {
            SourceRuntimeError.ScriptSyntax(messageFor(prefix, failure))
        } else {
            SourceRuntimeError.ScriptExecution(messageFor(prefix, failure))
        }

    /**
     * The binding reports every JavaScript failure as one exception type, so a syntax error can only
     * be told apart by the error name QuickJS puts in the message. Install-time failures are handled
     * separately and stay package errors, so this only affects the syntax/runtime distinction of a
     * call, never whether a source is accepted.
     */
    private fun isSyntaxError(failure: QuickJsException): Boolean =
        failure.message?.contains("SyntaxError") == true

    private fun validateCall(call: SourceCall.InvokeFunction): SourceRuntimeError.InvalidCall? {
        if (call.callId.isBlank()) {
            return SourceRuntimeError.InvalidCall("Call ID must not be blank.")
        }
        if (!SourceInvocationScript.validateFunctionName(call.functionName)) {
            return SourceRuntimeError.InvalidCall("Function name is not a valid identifier.")
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

    private fun SourceCall.failure(error: SourceRuntimeError): SourceResult.Failure =
        SourceResult.Failure(callId, error)

    private fun messageFor(prefix: String, failure: Throwable): String {
        val detail = failure.message?.trim()?.take(MAX_ERROR_DETAIL_LENGTH)
        return if (detail.isNullOrEmpty()) prefix else "$prefix: $detail"
    }

    private class RuntimeFailure(val error: SourceRuntimeError) : Exception(error.message)

    /**
     * One installed source: its engine, its script, and the scope its evaluations run in.
     *
     * The engine is nullable because a timeout or a cancellation leaves an engine that may still be
     * running the script that caused it; such an engine is dropped and rebuilt on the next call
     * rather than reused, which is the only safe move when it cannot be interrupted.
     */
    private inner class LoadedSource(
        private val source: SourcePackage,
        dispatcher: CoroutineDispatcher,
    ) : AutoCloseable {
        val invocationMutex = Mutex()

        private val scope = CoroutineScope(SupervisorJob() + dispatcher)
        private val engineRef = AtomicReference<QuickJs?>(null)

        suspend fun load(): QuickJs {
            val engine = createEngine()
            try {
                val bridge =
                    QuickJsHostBridge(
                        sourceId = source.sourceId,
                        hostApi = hostApi,
                        appLocale = appLocale,
                        appVersion = appVersion,
                        logSink = logSink,
                    )
                bridge.install(engine)
                bridge.loadBootstrap(engine)

                val sentinel =
                    engine.evaluate<String>(source.script + "\n;\"" + SOURCE_LOADED_SENTINEL + "\"\n")
                if (sentinel != SOURCE_LOADED_SENTINEL) {
                    throw RuntimeFailure(
                        SourceRuntimeError.InvalidPackage(
                            "Source script did not complete initialization.",
                        ),
                    )
                }

                engineRef.set(engine)
                return engine
            } catch (failure: Throwable) {
                closeDetached(engine)
                throw failure
            }
        }

        /**
         * The engine to evaluate on, rebuilding it after a dirty shutdown.
         *
         * Callers already hold [invocationMutex], so this must not take it again.
         */
        suspend fun engine(): QuickJs = engineRef.get() ?: load()

        fun evaluate(engine: QuickJs, script: String): Deferred<String> =
            scope.async { engine.evaluate<String>(script) }

        /** Drops the engine after an interruption but keeps the source installed. */
        fun discardEngine() {
            engineRef.getAndSet(null)?.let(::closeDetached)
        }

        fun discard() {
            scope.cancel()
            engineRef.getAndSet(null)?.let(::closeDetached)
        }

        override fun close() = discard()

        /**
         * Closing an engine that may still be running an uninterruptible script can block, so it
         * never happens on a caller's thread.
         */
        private fun closeDetached(engine: QuickJs) {
            Thread {
                runCatching { engine.close() }
            }.apply {
                isDaemon = true
                name = "quickjs-close"
            }.start()
        }

        private fun createEngine(): QuickJs =
            try {
                QuickJs.create(dispatcher)
            } catch (failure: Throwable) {
                throw RuntimeFailure(
                    SourceRuntimeError.EngineUnavailable(
                        messageFor("JavaScript engine could not be created", failure),
                    ),
                )
            }
    }

    private class ActiveCall {
        val cancelled = AtomicBoolean(false)
        private val evaluation = AtomicReference<Deferred<String>?>(null)

        fun attach(value: Deferred<String>) {
            check(evaluation.compareAndSet(null, value))
            if (cancelled.get()) {
                value.cancel()
            }
        }

        fun cancel() {
            cancelled.set(true)
            evaluation.get()?.cancel()
        }
    }

    private companion object {
        const val DEFAULT_INSTALL_TIMEOUT_MILLIS = 5_000L
        const val DEFAULT_MAX_RESULT_BYTES = 1_048_576
        const val MAX_CALL_TIMEOUT_MILLIS = 120_000L
        const val MAX_ERROR_DETAIL_LENGTH = 512
        const val SOURCE_LOADED_SENTINEL = "__VENERA_SOURCE_LOADED__"
        const val DEFAULT_APP_LOCALE = "en"
        const val DEFAULT_APP_VERSION = "0"
    }
}
