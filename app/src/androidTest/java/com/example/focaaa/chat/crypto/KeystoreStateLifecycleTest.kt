package com.example.focaaa.chat.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeystoreStateLifecycleTest {
    @Test
    fun rotatedAndDeletedSnapshotsCannotBeRestoredFromOldCopies() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val stateId = "test_" + UUID.randomUUID().toString().replace("-", "")
        val backing = InMemoryBlobStore()
        val firstStore = KeystoreRotatingCryptoStateStore(context, backing)
        val first = byteArrayOf(1, 2, 3, 4)
        val second = byteArrayOf(5, 6, 7, 8)
        try {
            firstStore.write(stateId, first)
            val retiredCopy = requireNotNull(backing.read(stateId))
            firstStore.write(stateId, second)
            val currentCopy = requireNotNull(backing.read(stateId))

            backing.write(stateId, retiredCopy)
            assertTrue("A retired MLS key still decrypted an old blob", runCatching { firstStore.read(stateId) }.isFailure)
            backing.write(stateId, currentCopy)

            val reopened = KeystoreRotatingCryptoStateStore(context, backing)
            assertArrayEquals(second, reopened.read(stateId))
            reopened.delete(stateId)
            assertNull(reopened.read(stateId))

            backing.write(stateId, currentCopy)
            assertTrue("A deleted MLS key still decrypted a restored blob", runCatching { reopened.read(stateId) }.isFailure)
        } finally {
            backing.delete(stateId)
            firstStore.delete(stateId)
            first.fill(0)
            second.fill(0)
        }
    }

    private class InMemoryBlobStore : EncryptedCryptoStateStore {
        private val blobs = mutableMapOf<String, ByteArray>()
        override suspend fun read(stateId: String): ByteArray? = blobs[stateId]?.copyOf()
        override suspend fun write(stateId: String, state: ByteArray) {
            blobs[stateId] = state.copyOf()
        }
        override suspend fun delete(stateId: String) {
            blobs.remove(stateId)?.fill(0)
        }
    }
}