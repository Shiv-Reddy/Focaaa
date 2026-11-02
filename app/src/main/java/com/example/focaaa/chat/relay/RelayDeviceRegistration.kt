package com.example.focaaa.chat.relay

import android.content.Context
import android.util.Log
import android.content.pm.ApplicationInfo
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.firebase.messaging.FirebaseMessaging
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Registers the current installation only when the user starts pairing. */
object RelayDeviceRegistration {
    private const val PREFERENCES = "focaaa_relay"
    private const val DEVICE_ID_KEY = "device_id"
    private val refreshInFlight = AtomicBoolean(false)

    fun register(context: Context, gateway: RelayGateway = WorkerRelayGateway()): Task<RelayResult> {
        val appContext = context.applicationContext
        val result = TaskCompletionSource<RelayResult>()
        val debug = appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (debug) Log.d("FocaaaRelay", "device registration started")
        gateway.ensureAnonymousSession()
            .addOnSuccessListener {
                fun submit(token: String) {
                    gateway.registerDevice(deviceId(appContext), token)
                        .addOnSuccessListener { response ->
                            if (debug) Log.d("FocaaaRelay", "device registration succeeded")
                            RelaySyncHintService.markRegistered(appContext)
                            result.setResult(response)
                        }
                        .addOnFailureListener { error ->
                            if (debug) Log.w("FocaaaRelay", "device registration failed: ${error.javaClass.simpleName}")
                            result.setException(error)
                        }
                }
                FirebaseMessaging.getInstance().token
                    .addOnSuccessListener { token -> submit(token) }
                    .addOnFailureListener { submit("") }
            }
            .addOnFailureListener { error ->
                if (debug) Log.w("FocaaaRelay", "anonymous session failed: ${error.javaClass.simpleName}")
                result.setException(error)
            }
        return result.task
    }

    /** Refreshes a rotated FCM token only for an installation already registered for pairing. */
    fun refreshRegistrationIfNeeded(context: Context) {
        if (!RelaySyncHintService.hasRegistered(context) || !RelaySyncHintService.needsRegistration(context)) return
        if (!refreshInFlight.compareAndSet(false, true)) return
        try {
            register(context).addOnCompleteListener { refreshInFlight.set(false) }
        } catch (_: Exception) {
            refreshInFlight.set(false)
        }
    }

    internal fun deviceId(context: Context): String {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        return preferences.getString(DEVICE_ID_KEY, null) ?: UUID.randomUUID().toString().also { id ->
            check(preferences.edit().putString(DEVICE_ID_KEY, id).commit()) {
                "Could not save this installation identifier."
            }
        }
    }
}
