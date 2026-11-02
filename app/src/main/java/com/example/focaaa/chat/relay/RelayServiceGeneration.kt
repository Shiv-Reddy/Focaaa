package com.example.focaaa.chat.relay

import android.content.Context

/** A local marker prevents old emulator pairs from silently sending to a fresh remote database. */
object RelayServiceGeneration {
    private const val PREFERENCES = "focaaa_worker_pairs"
    private const val KEY = "current_pair_ids"

    fun isCurrent(context: Context, conversationId: String): Boolean =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getStringSet(KEY, emptySet())?.contains(conversationId) == true

    fun markCurrent(context: Context, conversationId: String) {
        val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val ids = preferences.getStringSet(KEY, emptySet()).orEmpty().toMutableSet()
        ids.add(conversationId)
        check(preferences.edit().putStringSet(KEY, ids).commit()) { "Could not save relay pairing state." }
    }

    fun forget(context: Context, conversationId: String) {
        val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val ids = preferences.getStringSet(KEY, emptySet()).orEmpty().toMutableSet()
        ids.remove(conversationId)
        check(preferences.edit().putStringSet(KEY, ids).commit()) { "Could not update relay pairing state." }
    }
}
