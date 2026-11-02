package com.example.focaaa.chat.pairing

import com.example.focaaa.chat.data.PairingSessionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PairingSessionKeyTest {
    @Test
    fun pendingHandshakeStaysUnderCurrentKey() {
        val session = PairingSession(
            role = PairingRole.INVITER,
            stage = PairingStage.INVITE_READY,
            identityHandle = "identity",
            inviteToken = "token"
        )

        assertEquals(PairingSessionEntity.CURRENT_SESSION, pairingSessionStorageKey(session))
    }

    @Test
    fun activeConversationUsesItsOwnConversationKey() {
        val session = PairingSession(
            role = PairingRole.INVITER,
            stage = PairingStage.ACTIVE,
            identityHandle = "retired",
            conversationId = "conversation-123",
            relayActive = true
        )

        assertEquals("conversation-123", pairingSessionStorageKey(session))
    }

    @Test
    fun activeSessionWithoutConversationIdIsRejected() {
        val session = PairingSession(
            role = PairingRole.JOINER,
            stage = PairingStage.ACTIVE,
            identityHandle = "retired",
            relayActive = true
        )

        assertThrows(IllegalArgumentException::class.java) { pairingSessionStorageKey(session) }
    }
}
