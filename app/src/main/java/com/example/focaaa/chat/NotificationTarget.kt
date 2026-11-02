package com.example.focaaa.chat

/** A multi-contact tile must never guess which person will receive a reply. */
internal fun chooseNotificationContact(
    contacts: List<ConversationContact>,
    previouslySelectedId: String?
): ConversationContact? =
    contacts.firstOrNull { it.conversationId == previouslySelectedId }
        ?: contacts.singleOrNull()

/** Tile activation opens the only partner directly; several require an explicit choice. */
internal fun tileContactWithoutChoice(contacts: List<ConversationContact>): ConversationContact? =
    contacts.singleOrNull()

/** A posted reply stays with the exact contact shown when the notification was created. */
internal fun boundNotificationContact(
    contacts: List<ConversationContact>,
    postedConversationId: String?,
    currentlySelectedId: String?
): ConversationContact? =
    postedConversationId?.takeIf(String::isNotBlank)?.let { id ->
        chooseNotificationContact(contacts, currentlySelectedId)?.takeIf { it.conversationId == id }
    }
