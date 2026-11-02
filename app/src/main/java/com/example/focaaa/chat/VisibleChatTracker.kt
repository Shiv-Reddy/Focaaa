package com.example.focaaa.chat

import java.util.concurrent.atomic.AtomicReference

/** In-process presence only: a dead, paused, or background app never suppresses an alert. */
internal object VisibleChatTracker {
    private val visibleConversation = AtomicReference<String?>(null)

    fun enter(conversationId: String) {
        require(conversationId.isNotBlank() && conversationId.length <= 128)
        visibleConversation.set(conversationId)
    }

    fun leave(conversationId: String) {
        visibleConversation.compareAndSet(conversationId, null)
    }

    fun shouldAlert(conversationId: String): Boolean =
        visibleConversation.get() != conversationId
}
