package dev.veneranative.data.update

import java.util.concurrent.atomic.AtomicReference
import okhttp3.OkHttpClient

/**
 * The process-wide hook the update download worker uses to reach the shared HTTP client.
 *
 * The app layer owns the shared client (it carries the system proxy selector the user's VPN relies
 * on), but `:data:update` cannot depend on `:app`. The worker — which may outlive any screen — needs
 * the same client the About screen used to check for releases, so the app configures it once here at
 * startup and the worker reads it back.
 */
object UpdateEnvironment {
    private val clientRef = AtomicReference<OkHttpClient?>(null)

    /** Called by the app layer during graph assembly, before any worker can run. */
    fun configure(client: OkHttpClient) {
        clientRef.set(client)
    }

    /** Returns the shared client, or a bare default if the app has not configured one yet. */
    fun httpClient(): OkHttpClient = clientRef.get() ?: OkHttpClient()
}
