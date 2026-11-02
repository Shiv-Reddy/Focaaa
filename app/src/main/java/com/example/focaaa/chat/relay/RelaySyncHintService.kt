package com.example.focaaa.chat.relay

import android.content.Context
import com.example.focaaa.chat.ChatSessionNotification
import com.example.focaaa.chat.FocusReminderNotification
import com.example.focaaa.chat.VisibleChatTracker
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Receives silent queue-change hints; never displays message text or opens an Activity. */
class RelaySyncHintService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        if (token.isBlank()) return
        getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(REGISTRATION_PENDING, true)
            .apply()
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (message.data["type"] != SYNC_HINT) return
        val conversationId = message.data["conversationId"] ?: return
        if (conversationId.length !in 1..128 || !conversationId.all { it.isLetterOrDigit() || it == '-' || it == '_' }) return
        // A hint carries no message data. Durable envelope reconciliation happens in the app flow.
        pendingHints.tryEmit(conversationId)
        getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(SYNC_PENDING, true)
            .apply()
        if (VisibleChatTracker.shouldAlert(conversationId) &&
            !ChatSessionNotification.isOpenFor(applicationContext, conversationId)) {
            FocusReminderNotification.show(applicationContext)
        }
        ChatSessionNotification.refreshIfAlreadyOpen(applicationContext, conversationId)
    }

    companion object {
        private val pendingHints = MutableSharedFlow<String>(
            extraBufferCapacity = 8,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
        val syncHints = pendingHints.asSharedFlow()

        private const val PREFERENCES = "focaaa_relay"
        private const val REGISTRATION_PENDING = "registration_pending"
        private const val DEVICE_REGISTERED = "device_registered"
        private const val SYNC_PENDING = "sync_pending"
        private const val SYNC_HINT = "sync"

        fun consumePendingSyncHint(context: Context): Boolean {
            val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            val pending = preferences.getBoolean(SYNC_PENDING, false)
            if (pending) preferences.edit().putBoolean(SYNC_PENDING, false).apply()
            return pending
        }

        fun needsRegistration(context: Context): Boolean =
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getBoolean(REGISTRATION_PENDING, false)

        fun hasRegistered(context: Context): Boolean =
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getBoolean(DEVICE_REGISTERED, false)

        fun markRegistered(context: Context) {
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(REGISTRATION_PENDING, false)
                .putBoolean(DEVICE_REGISTERED, true)
                .apply()
        }
    }
}
