package com.example.focaaa.chat

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.focaaa.MainActivity
import com.example.focaaa.R
import kotlin.random.Random

/** Public notification copy is fixed locally; no pairing or message data reaches it. */
internal object FocusReminderCopy {
    private val prompts = listOf(
        "Focus check-in" to "Pick the smallest useful step and begin.",
        "A clear moment" to "Close one extra tab and give one task your attention.",
        "Your next step" to "Write down what you can finish in ten minutes.",
        "Reset your pace" to "Take a breath, then return to one priority.",
        "A little momentum" to "One focused block can move today forward.",
        "Time to refocus" to "Put distractions aside for the next few minutes.",
        "Keep it simple" to "Choose one task before choosing another.",
        "Make space" to "Clear your desk and start where you are.",
        "Steady progress" to "Finish one small thing before switching.",
        "Start fresh" to "Set a timer for the work in front of you.",
        "Quiet progress" to "A short pause can sharpen your next step.",
        "Today's plan" to "Check the task you most want to complete."
    )

    val count: Int get() = prompts.size

    fun at(index: Int): String = prompts[Math.floorMod(index, prompts.size)].second

    fun titleAt(index: Int): String = prompts[Math.floorMod(index, prompts.size)].first

    fun nextIndex(previous: Int, randomIndex: Int): Int {
        require(randomIndex in prompts.indices)
        return if (randomIndex == previous) (randomIndex + 1) % prompts.size else randomIndex
    }
}

/** A normal focus reminder shown for a content-free relay hint. */
internal object FocusReminderNotification {
    private const val CHANNEL_ID = "focaaa_focus_updates"
    private const val NOTIFICATION_ID = 4107
    private const val OPEN_REQUEST = 4108
    private const val COPY_PREFERENCES = "focaaa_focus_reminder_copy"
    private const val LAST_COPY_INDEX = "last_index"

    fun dismiss(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    fun show(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val platformManager = context.getSystemService(NotificationManager::class.java) ?: return
            platformManager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Focus reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Short prompts for returning to focus"
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                }
            )
            if (platformManager.getNotificationChannel(CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE) return
        }

        val openFocusHome = PendingIntent.getActivity(
            context,
            OPEN_REQUEST,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val promptIndex = nextPromptIndex(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_quick_settings)
            .setContentTitle(FocusReminderCopy.titleAt(promptIndex))
            .setContentText(FocusReminderCopy.at(promptIndex))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(openFocusHome)
            .build()
        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Notification permission may have changed since the FCM hint arrived.
        }
    }

    @Synchronized
    private fun nextPromptIndex(context: Context): Int {
        val preferences = context.getSharedPreferences(COPY_PREFERENCES, Context.MODE_PRIVATE)
        val previous = preferences.getInt(LAST_COPY_INDEX, -1)
        val next = FocusReminderCopy.nextIndex(previous, Random.nextInt(FocusReminderCopy.count))
        preferences.edit().putInt(LAST_COPY_INDEX, next).apply()
        return next
    }
}
