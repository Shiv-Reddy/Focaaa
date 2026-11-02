package com.example.focaaa.chat.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.focaaa.chat.crypto.EncryptedCryptoStateStore
import com.example.focaaa.chat.crypto.KeystoreRotatingCryptoStateStore
import java.security.SecureRandom
import java.util.UUID
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Runs against an actual SQLCipher file and Android Keystore, with disposable test data. */
@RunWith(AndroidJUnit4::class)
class SqlCipherKeystorePersistenceTest {
    @Test fun historyAndMlsStateSurviveReopenWhileRetiredStateCannotBeRestored() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        System.loadLibrary("sqlcipher")
        val name = "focaaa_persistence_test_${UUID.randomUUID()}.db"
        val stateId = "persistence_test_${UUID.randomUUID()}"
        val passphrase = ByteArray(32).also(SecureRandom()::nextBytes)
        val first = byteArrayOf(1, 2, 3, 4)
        val second = byteArrayOf(5, 6, 7, 8)
        fun open(): ConversationDatabase = Room.databaseBuilder(context, ConversationDatabase::class.java, name)
            .openHelperFactory(SupportOpenHelperFactory(passphrase.copyOf()))
            .build().also { it.openHelper.writableDatabase }

        var database: ConversationDatabase? = null
        try {
            database = open()
            val firstDao = database.conversationDao()
            val firstStore = KeystoreRotatingCryptoStateStore(context, TestRoomBlobStore(firstDao))
            firstDao.insertConversation(ConversationEntity("test-chat", "Test contact"))
            firstDao.insertMessage(MessageEntity("test-message", "test-chat", "Private persisted text", "LOCAL_USER", 100))
            firstStore.write(stateId, first)
            val retiredBlob = requireNotNull(firstDao.getCryptoState(stateId)).state.copyOf()
            firstStore.write(stateId, second)
            val currentBlob = requireNotNull(firstDao.getCryptoState(stateId)).state.copyOf()
            database.close()
            database = null

            assertFalse(context.getDatabasePath(name).readBytes().toString(Charsets.ISO_8859_1).contains("Private persisted text"))
            database = open()
            val reopenedDao = database.conversationDao()
            val reopened = KeystoreRotatingCryptoStateStore(context, TestRoomBlobStore(reopenedDao))
            assertEquals("Private persisted text", reopenedDao.getMessage("test-message")?.text)
            assertArrayEquals(second, reopened.read(stateId))

            reopenedDao.upsertCryptoState(CryptoStateEntity(stateId, retiredBlob))
            assertTrue(runCatching { reopened.read(stateId) }.isFailure)
            reopenedDao.upsertCryptoState(CryptoStateEntity(stateId, currentBlob))
            reopened.delete(stateId)
            assertNull(reopened.read(stateId))
        } finally {
            database?.let { openDatabase ->
                runCatching {
                    KeystoreRotatingCryptoStateStore(context, TestRoomBlobStore(openDatabase.conversationDao())).delete(stateId)
                }
            }
            database?.close()
            context.deleteDatabase(name)
            passphrase.fill(0)
            first.fill(0)
            second.fill(0)
        }
    }

    private class TestRoomBlobStore(private val dao: ConversationDao) : EncryptedCryptoStateStore {
        override suspend fun read(stateId: String): ByteArray? = dao.getCryptoState(stateId)?.state?.copyOf()
        override suspend fun write(stateId: String, state: ByteArray) {
            dao.upsertCryptoState(CryptoStateEntity(stateId, state.copyOf()))
        }
        override suspend fun delete(stateId: String) = dao.deleteCryptoState(stateId)
    }
}
