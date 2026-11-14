package dev.veneranative.data.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import java.io.File
import java.io.IOException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

sealed interface ApkInstallResult {
    /** The session committed; the system replaces the package (the app process may be killed). */
    data object Success : ApkInstallResult

    data class Failure(val status: Int, val message: String?) : ApkInstallResult
}

/**
 * Session-based `PackageInstaller` wrapper for self-updates. When the platform demands user
 * confirmation the system install dialog is launched from here, so callers never touch intents;
 * `install` suspends until the system reports the final outcome. A fully silent install is not
 * possible for a sideloaded app — one system confirmation is the platform minimum.
 */
class ApkInstaller {

    fun install(context: Context, apk: File, onOutcome: (ApkInstallResult) -> Unit) {
        val appContext = context.applicationContext
        val session = openSessionWithApk(appContext.packageManager.packageInstaller, apk)
        if (session == null) {
            onOutcome(ApkInstallResult.Failure(PackageInstaller.STATUS_FAILURE, "unable to write the install session"))
            return
        }
        registerStatusReceiver(appContext) { event ->
            when (event) {
                is ApkInstallEvent.ConfirmationRequired -> {
                    // The system wants the user to approve the install; hand the activity over.
                    runCatching { appContext.startActivity(event.confirmIntent) }
                        .onFailure {
                            unregisterReceiver(appContext)
                            onOutcome(ApkInstallResult.Failure(PackageInstaller.STATUS_FAILURE, "unable to start the system confirmation"))
                        }
                }
                is ApkInstallEvent.Finished -> {
                    unregisterReceiver(appContext)
                    onOutcome(event.result)
                }
            }
        }
        val statusIntent = Intent(ACTION_INSTALL_STATUS).setPackage(appContext.packageName)
        val pending = PendingIntent.getBroadcast(
            appContext,
            0,
            statusIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        try {
            session.commit(pending.intentSender)
        } catch (error: Exception) {
            unregisterReceiver(appContext)
            session.abandon()
            onOutcome(ApkInstallResult.Failure(PackageInstaller.STATUS_FAILURE, "the install session was rejected: ${error.message}"))
            return
        }
        session.close()
    }

    private fun openSessionWithApk(installer: PackageInstaller, apk: File): PackageInstaller.Session? {
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (Build.VERSION.SDK_INT >= 31) {
            // Claim update ownership where supported so future self-updates may skip the dialog.
            runCatching { params.setRequestUpdateOwnership(true) }
        }
        val session = installer.openSession(installer.createSession(params))
        try {
            apk.inputStream().use { input ->
                session.openWrite("base.apk", 0, apk.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }
        } catch (_: IOException) {
            session.abandon()
            return null
        }
        return session
    }

    private fun registerStatusReceiver(context: Context, onEvent: (ApkInstallEvent) -> Unit) {
        unregisterReceiver(context)
        receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        val confirmIntent = extractConfirmIntent(intent)
                        if (confirmIntent == null) {
                            onEvent(
                                ApkInstallEvent.Finished(
                                    ApkInstallResult.Failure(
                                        PackageInstaller.STATUS_FAILURE,
                                        "the installer did not provide a confirmation intent",
                                    ),
                                ),
                            )
                        } else {
                            confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            onEvent(ApkInstallEvent.ConfirmationRequired(confirmIntent))
                        }
                    }
                    PackageInstaller.STATUS_SUCCESS ->
                        onEvent(ApkInstallEvent.Finished(ApkInstallResult.Success))
                    else -> onEvent(
                        ApkInstallEvent.Finished(
                            ApkInstallResult.Failure(
                                intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE),
                                intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE),
                            ),
                        ),
                    )
                }
            }
        }.also { receiver ->
            val filter = IntentFilter(ACTION_INSTALL_STATUS)
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(receiver, filter)
            }
            registeredContext = context
        }
    }

    private fun unregisterReceiver(context: Context) {
        val current = receiver ?: return
        runCatching { context.unregisterReceiver(current) }
        if (registeredContext === context) {
            receiver = null
            registeredContext = null
        }
    }

    private fun extractConfirmIntent(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }

    private sealed interface ApkInstallEvent {
        data class ConfirmationRequired(val confirmIntent: Intent) : ApkInstallEvent
        data class Finished(val result: ApkInstallResult) : ApkInstallEvent
    }

    private var receiver: BroadcastReceiver? = null
    private var registeredContext: Context? = null

    companion object {
        private const val ACTION_INSTALL_STATUS = "dev.veneranative.data.update.INSTALL_STATUS"
    }
}
