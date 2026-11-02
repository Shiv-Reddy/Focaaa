package com.example.focaaa.chat.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MultiContactDaoTest {
    @Test
    fun activeContactsKeepSeparateMessagesAndPendingInvite() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, ConversationDatabase::class.java).build()
        try {
            val dao = database.conversationDao()
            fun active(id: String) = PairingSessionEntity(
                sessionId = id,
                role = "INVITER",
                stage = "ACTIVE",
                identityHandle = "synthetic-identity",
                inviteToken = null,
                expiresAtMillis = 0L,
                conversationId = id,
                keyPackage = null,
                welcome = null,
                ratchetTree = null,
                verificationCode = null,
                localControlQueued = false,
                relayActive = true
            )
            dao.savePairingSessionForConversation(active("contact_a"))
            dao.savePairingSessionForConversation(active("contact_b"))
            dao.renameConversationLocally("contact_a", "Alice")
            dao.renameConversationLocally("contact_b", "Bob")
            dao.insertMessage(MessageEntity("message_a", "contact_a", "A", "LOCAL_USER", 1L))
            dao.insertMessage(MessageEntity("message_b", "contact_b", "B", "LOCAL_USER", 2L))
            dao.upsertPairingVerification(PairingVerificationEntity("contact_a", byteArrayOf(1), "123456789012", true, true, false))
            dao.upsertPairingVerification(PairingVerificationEntity("contact_b", byteArrayOf(2), "123456789012", true, true, false))
            dao.upsertPairingSession(active("contact_c").copy(
                sessionId = PairingSessionEntity.CURRENT_SESSION,
                stage = "INVITE_READY",
                relayActive = false
            ))

            assertEquals(listOf("contact_a", "contact_b"), dao.observeActiveContacts().first().map { it.conversationId })
            assertEquals(listOf("message_a"), dao.observeMessages("contact_a").first().map { it.id })
            assertEquals(listOf("message_b"), dao.observeMessages("contact_b").first().map { it.id })
            assertEquals("INVITE_READY", dao.getPairingSession(PairingSessionEntity.CURRENT_SESSION)?.stage)

            dao.deletePairingAndConversationData("contact_a")
            assertNull(dao.getPairingSession("contact_a"))
            assertNull(dao.getPairingVerification("contact_a"))
            assertEquals(listOf("contact_b"), dao.observeActiveContacts().first().map { it.conversationId })
            assertEquals(listOf("message_b"), dao.observeMessages("contact_b").first().map { it.id })
            assertEquals(true, dao.getPairingVerification("contact_b")?.localConfirmed)
            assertEquals("INVITE_READY", dao.getPairingSession(PairingSessionEntity.CURRENT_SESSION)?.stage)
        } finally {
            database.close()
        }
    }
}
