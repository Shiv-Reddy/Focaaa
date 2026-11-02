package com.example.focaaa.chat.pairing

import com.example.focaaa.chat.ConversationContact

/** The first release supports one live partner; imported history is read only. */
internal fun hasActivePartner(contacts: List<ConversationContact>): Boolean =
    contacts.any { !it.isRestored }
