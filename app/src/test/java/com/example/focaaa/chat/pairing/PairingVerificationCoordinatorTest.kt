package com.example.focaaa.chat.pairing

import com.example.focaaa.chat.crypto.CryptoGroupHandle
import com.example.focaaa.chat.crypto.CryptoPeerIdentity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingVerificationCoordinatorTest {
    private val group = CryptoGroupHandle("test-conversation")
    private val peer = CryptoPeerIdentity(byteArrayOf(1, 2, 3))
    private val code = PairingVerificationCode("123456789012")

    @Test
    fun localConfirmationSucceedsBeforePeerWhileSendingRemainsLocked() = runBlocking {
        val store = InMemoryStore()
        val coordinator = PairingVerificationCoordinator.open(group, peer, code, store)

        assertFalse(coordinator.confirmLocalCode("000000000000"))
        assertFalse(coordinator.canSendApplicationMessages)
        assertTrue(coordinator.confirmLocalCode(code.value))
        assertTrue(store.snapshot?.localConfirmed == true)
        assertFalse(coordinator.canSendApplicationMessages)

        assertTrue(coordinator.confirmPeerCode(peer, code.value))
        assertTrue(coordinator.canSendApplicationMessages)
    }

    private class InMemoryStore : PairingVerificationStore {
        var snapshot: PairingVerificationSnapshot? = null

        override suspend fun read(group: CryptoGroupHandle): PairingVerificationSnapshot? = snapshot

        override suspend fun write(group: CryptoGroupHandle, snapshot: PairingVerificationSnapshot) {
            this.snapshot = snapshot
        }

        override suspend fun delete(group: CryptoGroupHandle) {
            snapshot = null
        }
    }
}
