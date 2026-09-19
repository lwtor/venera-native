package dev.veneranative.source.engine

import androidx.javascriptengine.JavaScriptSandbox

/** Capability probe kept separate from runtime creation for UI and diagnostics. */
object JavaScriptEngineSupport {
    fun isSupported(): Boolean = JavaScriptSandbox.isSupported()
}
