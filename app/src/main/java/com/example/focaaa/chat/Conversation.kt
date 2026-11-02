package com.example.focaaa.chat

data class Conversation(
    val id: String,
    val title: String
)

data class ConversationContact(
    val conversationId: String,
    val displayName: String,
    val isRestored: Boolean = false
)

fun normalizeContactName(value: String): String? {
    val clean = value.trim()
    return clean.takeIf { it.isNotEmpty() && it.length <= 40 }
}

fun contactDisplayName(savedTitle: String?): String =
    savedTitle?.takeIf { it.isNotBlank() && !it.equals("Partner", ignoreCase = true) }
        ?: "Private contact"

fun contactInitials(name: String): String = name.trim()
    .split(Regex("\\s+"))
    .filter(String::isNotBlank)
    .take(2)
    .joinToString("") { it.first().uppercaseChar().toString() }
    .ifEmpty { "?" }

enum class MessageSender {
    LOCAL_USER,
    DEMO_PARTNER,
    REMOTE_PARTNER
}

enum class MessageDeliveryStatus(val level: Int) {
    QUEUED(0), SENT(1), DELIVERED(2), READ(3);

    fun advanceTo(level: Int): MessageDeliveryStatus = fromLevel(maxOf(this.level, level))

    companion object {
        fun fromLevel(level: Int): MessageDeliveryStatus = entries.firstOrNull { it.level == level }
            ?: throw IllegalArgumentException("Unsupported message delivery level.")
    }
}

data class ConversationMessage(
    val id: String,
    val conversationId: String,
    val text: String,
    val sender: MessageSender,
    val createdAtMillis: Long,
    val deliveryStatus: MessageDeliveryStatus = MessageDeliveryStatus.SENT
)
