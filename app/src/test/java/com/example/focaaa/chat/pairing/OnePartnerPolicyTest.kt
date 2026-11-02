package com.example.focaaa.chat.pairing

import com.example.focaaa.chat.ConversationContact
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnePartnerPolicyTest {
    @Test fun restoredHistoryDoesNotPreventAFirstPairing() {
        assertFalse(hasActivePartner(emptyList()))
        assertFalse(hasActivePartner(listOf(ConversationContact("backup", "Past chat", isRestored = true))))
    }

    @Test fun anExistingActivePartnerPreventsAnotherInviteOrJoin() {
        val active = ConversationContact("live", "Partner")
        assertTrue(hasActivePartner(listOf(active)))
        assertTrue(hasActivePartner(listOf(ConversationContact("backup", "Past chat", true), active)))
    }
}
