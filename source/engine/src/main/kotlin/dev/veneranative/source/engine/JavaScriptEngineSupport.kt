package dev.veneranative.source.engine

import android.content.Context
import androidx.javascriptengine.JavaScriptSandbox

/**
 * The first seam around AndroidX JavaScriptEngine.
 *
 * Keeping availability checks behind this class prevents feature code from depending directly on
 * the sandbox implementation and leaves room for a future fallback runtime.
 */
class JavaScriptEngineSupport(
    private val context: Context,
) {
    fun isSupported(): Boolean = JavaScriptSandbox.isSupported()

    fun applicationContext(): Context = context.applicationContext
}
