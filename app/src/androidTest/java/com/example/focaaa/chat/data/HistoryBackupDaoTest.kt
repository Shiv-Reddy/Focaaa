package com.example.focaaa.chat.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.focaaa.chat.MessageSender
import com.example.focaaa.chat.backup.HistoryConversation
import com.example.focaaa.chat.backup.HistoryMessage
import com.example.focaaa.chat.backup.HistorySnapshot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryBackupDaoTest {
    @Test fun importIsReadOnlyIdempotentAndDoesNotTouchAnActivePair() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, ConversationDatabase::class.java).build()
        try {
            val dao = database.conversationDao()
            dao.insertConversation(ConversationEntity("live-pair", "Live person"))
            dao.upsertPairingSession(PairingSessionEntity(
                sessionId = "live-pair", role = "INVITER", stage = "ACTIVE", identityHandle = "identity",
                inviteToken = null, expiresAtMillis = 0, conversationId = "live-pair", keyPackage = null,
                welcome = null, ratchetTree = null, verificationCode = null,
                localControlQueued = false, relayActive = true
            ))
            dao.insertMessage(MessageEntity("live-message", "live-pair", "Existing", "LOCAL_USER", 10))

            val snapshot = HistorySnapshot(
                "f7a826f742bd4413a6bc5c0da3f8c109", 1_800_000_000_000,
                listOf(HistoryConversation("old-pair", "Saved person", listOf(
                    HistoryMessage("old-message", MessageSender.REMOTE_PARTNER, 20, "Restored")
                )))
            )
            dao.importSavedHistory(snapshot)
            val archiveId = dao.observePrivateContacts().first().single { it.isRestored }.conversationId
            dao.renameConversationLocally(archiveId, "Saved renamed")
            dao.importSavedHistory(snapshot)

            val privateContacts = dao.observePrivateContacts().first()
            assertEquals(2, privateContacts.size)
            val archive = privateContacts.single { it.isRestored }
            assertTrue(archive.conversationId.startsWith("archive_"))
            assertEquals("Saved renamed", archive.title)
            assertEquals(1, dao.getBackupMessages(archive.conversationId).size)
            assertEquals(1, dao.observeActiveContacts().first().size)
            assertEquals("Existing", dao.getMessage("live-message")?.text)
            assertEquals(null, dao.getPairingSession(archive.conversationId))
            assertFalse(privateContacts.single { !it.isRestored }.conversationId.startsWith("archive_"))
            dao.deletePairingAndConversationData(archive.conversationId)
            assertEquals(1, dao.observePrivateContacts().first().size)
            assertEquals("Existing", dao.getMessage("live-message")?.text)
        } finally { database.close() }
    }

    @Test fun exportExcludesDemoAndIncludesActiveAndRestoredHistory() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, ConversationDatabase::class.java).build()
        try {
            val dao = database.conversationDao()
            dao.insertConversation(ConversationEntity("synthetic-demo", "Demo partner"))
            dao.insertMessage(MessageEntity("demo-1", "synthetic-demo", "Fake", "DEMO_PARTNER", 10))
            dao.importSavedHistory(HistorySnapshot(
                "f7a826f742bd4413a6bc5c0da3f8c109", 1_800_000_000_000,
                listOf(HistoryConversation("old-pair", "Saved person", listOf(
                    HistoryMessage("old-message", MessageSender.REMOTE_PARTNER, 20, "Restored")
                )))
            ))
            val records = dao.exportHistoryRecords()
            assertEquals(1, records.size)
            assertEquals("Saved person", records.single().title)
            assertEquals("Restored", records.single().messages.single().text)
        } finally { database.close() }
    }
}
