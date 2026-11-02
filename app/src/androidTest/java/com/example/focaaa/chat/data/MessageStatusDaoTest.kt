package com.example.focaaa.chat.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MessageStatusDaoTest {
    @Test fun acceptedEnvelopeAndMonotonicStatusesPersistWithInboundReadReporting() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, ConversationDatabase::class.java).build()
        try {
            val dao = database.conversationDao()
            dao.insertConversation(ConversationEntity("pair", "Private contact"))
            dao.insertMessage(MessageEntity("out-1", "pair", "hello", "LOCAL_USER", 100L, deliveryLevel = 0))
            dao.insertMessage(MessageEntity("in-1", "pair", "hi", "REMOTE_PARTNER", 101L))
            dao.insertRelayEnvelope(RelayEnvelopeEntity("out-1", "pair", "OUTBOUND", "QUEUED", byteArrayOf(1), 100L, 200L))

            dao.markOutboundAcceptedAndRemove("out-1")
            assertNull(dao.getRelayEnvelope("out-1"))
            assertEquals(1, dao.getMessage("out-1")?.deliveryLevel)
            dao.advanceOutgoingDeliveryLevel("pair", "out-1", 3)
            dao.advanceOutgoingDeliveryLevel("pair", "out-1", 2)
            assertEquals(3, dao.getMessage("out-1")?.deliveryLevel)
            assertEquals(listOf("in-1"), dao.getUnreportedInboundIds("pair", 0L))
            dao.markInboundReadReported("pair", "in-1")
            assertEquals(emptyList<String>(), dao.getUnreportedInboundIds("pair", 0L))
        } finally { database.close() }
    }
}
