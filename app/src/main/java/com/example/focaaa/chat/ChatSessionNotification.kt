package com.example.focaaa.chat

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.graphics.Color
import android.widget.RemoteViews
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import com.example.focaaa.R
import com.example.focaaa.chat.data.RoomConversationRepository
import com.example.focaaa.chat.pairing.PairingFlowCoordinator
import com.example.focaaa.chat.media.MediaMessageCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Posts an explicitly opened, contact-bound chat in Android's notification UI. */
internal object ChatSessionNotification {
    const val NOTIFICATION_ID = 4103
    const val CHANNEL_ID = "focaaa_chat_session"
    const val ACTION_REPLY = "com.example.focaaa.action.INLINE_REPLY"
    const val ACTION_DISMISS = "com.example.focaaa.action.DISMISS_CHAT_SESSION"
    const val ACTION_CHOOSE_CONTACT = "com.example.focaaa.action.CHOOSE_NOTIFICATION_CONTACT"
    const val ACTION_MORE_CONTACTS = "com.example.focaaa.action.MORE_NOTIFICATION_CONTACTS"
    const val EXTRA_REPLY = "com.example.focaaa.extra.REPLY"
    const val EXTRA_CONVERSATION_ID = "com.example.focaaa.extra.CONVERSATION_ID"
    private const val REPLY_REQUEST = 4104
    private const val DISMISS_REQUEST = 4105
    private const val CHOOSE_REQUEST = 4108
    private const val MORE_REQUEST = 4109
    private const val CONTACTS_PER_PAGE = 2
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private const val TARGET_PREFERENCES = "focaaa_notification_target"
    private const val TARGET_KEY = "conversation_id"
    private const val POSTED_KEY = "posted_conversation_id"

    fun selectContact(context: Context, conversationId: String) {
        require(conversationId.isNotBlank() && conversationId.length <= 128)
        val preferences = context.getSharedPreferences(TARGET_PREFERENCES, Context.MODE_PRIVATE)
        if (preferences.getString(TARGET_KEY, null) != conversationId) dismiss(context)
        preferences.edit().putString(TARGET_KEY, conversationId).apply()
    }

    fun forgetContact(context: Context, conversationId: String) {
        val preferences = context.getSharedPreferences(TARGET_PREFERENCES, Context.MODE_PRIVATE)
        if (preferences.getString(TARGET_KEY, null) == conversationId) {
            preferences.edit().remove(TARGET_KEY).apply()
        }
        dismiss(context)
    }

    private fun selectedId(context: Context): String? =
        context.getSharedPreferences(TARGET_PREFERENCES, Context.MODE_PRIVATE)
            .getString(TARGET_KEY, null)

    /** The existing, user-opened panel suppresses duplicate focus alerts for its exact contact. */
    fun isOpenFor(context: Context, conversationId: String): Boolean =
        isPosted(context) &&
            context.getSharedPreferences(TARGET_PREFERENCES, Context.MODE_PRIVATE)
                .getString(POSTED_KEY, null) == conversationId

    /** A quiet push hint can refresh only a panel the user already opened. */
    fun refreshIfAlreadyOpen(context: Context, hintedConversationId: String) {
        if (!isPosted(context)) return
        applicationScope.launch {
            try {
                val repository = RoomConversationRepository.open(context)
                val contact = chooseNotificationContact(repository.observeActiveContacts().first(), selectedId(context))
                    ?.takeIf { it.conversationId == hintedConversationId }
                    ?: return@launch
                PairingFlowCoordinator(context, repository, sessionId = contact.conversationId)
                    .syncActiveConversation(contact.conversationId)
                if (!isPosted(context) || boundNotificationContact(
                        repository.observeActiveContacts().first(), contact.conversationId, selectedId(context)
                    ) == null
                ) return@launch
                post(context, contact, repository.messagesFor(contact.conversationId).first(), null)
            } catch (_: Exception) {
                // Foreground opening and another tile activation can still reconcile later.
            }
        }
    }

    private fun isPosted(context: Context): Boolean = try {
        context.getSystemService(NotificationManager::class.java)
            ?.activeNotifications
            ?.any { it.id == NOTIFICATION_ID && it.packageName == context.packageName } == true
    } catch (_: SecurityException) {
        false
    }

    fun activate(context: Context, requestedId: String? = null, finish: (() -> Unit)? = null) {
        applicationScope.launch {
            try {
                val repository = RoomConversationRepository.open(context)
                val contacts = repository.observeActiveContacts().first()
                if (requestedId == null && contacts.size > 1) {
                    postContactChooser(context, contacts, 0)
                    return@launch
                }
                val contact = if (requestedId == null) tileContactWithoutChoice(contacts)
                    else contacts.firstOrNull { it.conversationId == requestedId }
                if (contact == null) {
                    if (requestedId == null) showHint(context, "Add a partner in the private area first.")
                    else dismiss(context)
                    return@launch
                }
                if (requestedId != null) selectContact(context, contact.conversationId)
                val status = try {
                    PairingFlowCoordinator(context, repository, sessionId = contact.conversationId)
                        .syncActiveConversation(contact.conversationId)
                    null
                } catch (_: Exception) {
                    "Showing saved messages. Connection unavailable."
                }
                if (boundNotificationContact(repository.observeActiveContacts().first(), contact.conversationId, selectedId(context)) == null) {
                    dismiss(context)
                    return@launch
                }
                post(context, contact, repository.messagesFor(contact.conversationId).first(), status)
            } catch (_: Exception) {
                showHint(context, "Private storage is unavailable.")
            } finally {
                finish?.invoke()
            }
        }
    }

    fun showNextContactPage(context: Context, page: Int, finish: () -> Unit) {
        applicationScope.launch {
            var delegated = false
            try {
                val contacts = RoomConversationRepository.open(context).observeActiveContacts().first()
                if (contacts.size > 1) postContactChooser(context, contacts, page)
                else {
                    delegated = true
                    activate(context, finish = finish)
                }
            } catch (_: Exception) {
                showHint(context, "Private storage is unavailable.")
            } finally {
                if (!delegated) finish()
            }
        }
    }

    fun saveInlineReply(context: Context, conversationId: String?, reply: String, finish: () -> Unit) {
        applicationScope.launch {
            try {
                val repository = RoomConversationRepository.open(context)
                val contact = boundNotificationContact(
                    repository.observeActiveContacts().first(), conversationId, selectedId(context)
                )
                if (contact == null) {
                    dismiss(context)
                    return@launch
                }
                val status = try {
                    val savedLocally = PairingFlowCoordinator(context, repository, sessionId = contact.conversationId)
                        .sendMessage(reply)
                    if (savedLocally) null else "Saved on this phone. Delivery unconfirmed."
                } catch (_: Exception) {
                    "Reply could not be saved."
                }
                if (boundNotificationContact(repository.observeActiveContacts().first(), contact.conversationId, selectedId(context)) == null) {
                    dismiss(context)
                    return@launch
                }
                post(context, contact, repository.messagesFor(contact.conversationId).first(), status)
            } catch (_: Exception) {
                // Do not log notification input, message text, or private storage errors.
            } finally {
                finish()
            }
        }
    }

    fun dismiss(context: Context) {
        context.getSharedPreferences(TARGET_PREFERENCES, Context.MODE_PRIVATE)
            .edit().remove(POSTED_KEY).apply()
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun showHint(context: Context, message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun postContactChooser(context: Context, contacts: List<ConversationContact>, requestedPage: Int) {
        createChannel(context)
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val pageCount = (contacts.size + CONTACTS_PER_PAGE - 1) / CONTACTS_PER_PAGE
        val page = requestedPage.coerceIn(0, pageCount - 1)
        context.getSharedPreferences(TARGET_PREFERENCES, Context.MODE_PRIVATE)
            .edit().remove(TARGET_KEY).remove(POSTED_KEY).apply()
        val dismissIntent = PendingIntent.getBroadcast(
            context, DISMISS_REQUEST,
            Intent(context, ChatSessionDismissReceiver::class.java).setAction(ACTION_DISMISS),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_quick_settings)
            .setContentTitle("Focaaa")
            .setContentText("Choose a private conversation · ${page + 1}/$pageCount")
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .setDeleteIntent(dismissIntent)
        contacts.drop(page * CONTACTS_PER_PAGE).take(CONTACTS_PER_PAGE).forEach { contact ->
            val chooseIntent = PendingIntent.getBroadcast(
                context, CHOOSE_REQUEST,
                Intent(context, ChatContactChoiceReceiver::class.java)
                    .setAction(ACTION_CHOOSE_CONTACT)
                    .setData(Uri.parse("focaaa://choose/contact/${Uri.encode(contact.conversationId)}")),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(R.drawable.ic_quick_settings, contact.displayName, chooseIntent)
        }
        if (pageCount > 1) {
            val nextPage = (page + 1) % pageCount
            val moreIntent = PendingIntent.getBroadcast(
                context, MORE_REQUEST,
                Intent(context, ChatContactChoiceReceiver::class.java)
                    .setAction(ACTION_MORE_CONTACTS)
                    .setData(Uri.parse("focaaa://choose/page/$nextPage")),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(R.drawable.ic_quick_settings, "More people", moreIntent)
        }
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build())
        } catch (_: SecurityException) {
            // Notification permission may have changed after the tile tap.
        }
    }

    private fun post(context: Context, contact: ConversationContact, messages: List<ConversationMessage>, status: String?) {
        createChannel(context)
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = context.getSystemService(NotificationManager::class.java)
                ?.getNotificationChannel(CHANNEL_ID)
            if (channel == null || channel.importance == NotificationManager.IMPORTANCE_NONE) return
        }

        val expandedChat = expandedChatView(context, contact, messages)

        val dismissIntent = PendingIntent.getBroadcast(
            context,
            DISMISS_REQUEST,
            Intent(context, ChatSessionDismissReceiver::class.java).setAction(ACTION_DISMISS),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val replyIntent = PendingIntent.getBroadcast(
            context,
            REPLY_REQUEST,
            Intent(context, ChatSessionReplyReceiver::class.java)
                .setAction(ACTION_REPLY)
                .setData(Uri.parse("focaaa://reply/${Uri.encode(contact.conversationId)}"))
                .putExtra(EXTRA_CONVERSATION_ID, contact.conversationId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        val replyAction = NotificationCompat.Action.Builder(
            R.drawable.ic_quick_settings,
            "Reply",
            replyIntent
        )
            .addRemoteInput(RemoteInput.Builder(EXTRA_REPLY).setLabel("Write a reply").build())
            .setAllowGeneratedReplies(false)
            .build()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_quick_settings)
            .setContentTitle("Focaaa")
            .setContentText(status ?: "Expand to view recent activity")
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomBigContentView(expandedChat)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .setDeleteIntent(dismissIntent)
            .addAction(replyAction)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
            context.getSharedPreferences(TARGET_PREFERENCES, Context.MODE_PRIVATE)
                .edit().putString(POSTED_KEY, contact.conversationId).apply()
            FocusReminderNotification.dismiss(context)
        } catch (_: SecurityException) {
            // Notification permission may have been revoked after the tile tap.
        }
    }

    private fun expandedChatView(context: Context, contact: ConversationContact, messages: List<ConversationMessage>): RemoteViews {
        val view = RemoteViews(context.packageName, R.layout.notification_chat_expanded)
        val recentMessages = messages.takeLast(MAX_EXPANDED_MESSAGES)
        val zone = ZoneId.systemDefault()
        val timeFormat = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault())
        view.setTextViewText(
            R.id.notification_chat_subtitle,
            if (recentMessages.isEmpty()) "${contact.displayName} · no messages yet"
            else "${contact.displayName} · last ${recentMessages.size} messages"
        )
        recentMessages.forEach { message ->
            val outgoing = message.sender == MessageSender.LOCAL_USER
            val row = RemoteViews(
                context.packageName,
                if (outgoing) R.layout.notification_chat_message_outgoing else R.layout.notification_chat_message_incoming
            )
            row.setTextViewText(R.id.notification_message_text, MediaMessageCodec.preview(message.text))
            val time = Instant.ofEpochMilli(message.createdAtMillis).atZone(zone).toLocalTime().format(timeFormat)
            val mark = if (outgoing) when (message.deliveryStatus) {
                MessageDeliveryStatus.QUEUED -> " · ◷"
                MessageDeliveryStatus.SENT -> " · ✓"
                MessageDeliveryStatus.DELIVERED, MessageDeliveryStatus.READ -> " · ✓✓"
            } else ""
            row.setTextViewText(R.id.notification_message_meta, time + mark)
            if (outgoing && message.deliveryStatus == MessageDeliveryStatus.READ) {
                row.setTextColor(R.id.notification_message_meta, Color.rgb(43, 132, 167))
            }
            view.addView(R.id.notification_chat_message_list, row)
        }
        return view
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Notification-panel chat",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Quiet, user-opened inline chat session; no incoming message alerts"
            lockscreenVisibility = android.app.Notification.VISIBILITY_SECRET
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
        }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private const val MAX_EXPANDED_MESSAGES = 4
}

class ChatSessionReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val conversationId = intent.getStringExtra(ChatSessionNotification.EXTRA_CONVERSATION_ID)
        if (conversationId.isNullOrBlank() || intent.data?.lastPathSegment != conversationId) return
        val reply = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(ChatSessionNotification.EXTRA_REPLY)
            ?.toString()
            ?.trim()
            .orEmpty()
        if (reply.isNotEmpty()) {
            val pendingResult = goAsync()
            ChatSessionNotification.saveInlineReply(
                context.applicationContext,
                conversationId,
                reply,
                pendingResult::finish
            )
        }
    }
}

class ChatSessionDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ChatSessionNotification.dismiss(context.applicationContext)
    }
}

class ChatContactChoiceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ChatSessionNotification.ACTION_CHOOSE_CONTACT -> {
                val id = intent.data?.takeIf { it.host == "choose" && it.pathSegments.firstOrNull() == "contact" }
                    ?.lastPathSegment?.takeIf { it.isNotBlank() && it.length <= 128 } ?: return
                val pending = goAsync()
                ChatSessionNotification.activate(context.applicationContext, id, pending::finish)
            }
            ChatSessionNotification.ACTION_MORE_CONTACTS -> {
                val page = intent.data?.takeIf { it.host == "choose" && it.pathSegments.firstOrNull() == "page" }
                    ?.lastPathSegment?.toIntOrNull()?.takeIf { it >= 0 } ?: return
                val pending = goAsync()
                ChatSessionNotification.showNextContactPage(context.applicationContext, page, pending::finish)
            }
        }
    }
}
