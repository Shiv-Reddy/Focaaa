package com.example.focaaa.chat.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DraftFrameCryptoTest {
    private val key = ByteArray(32) { it.toByte() }
    private val edit = DraftEdit.between("hi secret", "hi ", 7, 123456)

    @Test fun roundTripPreservesDeletedTextAndTimestamp() {
        val frame = DraftFrameCrypto.encrypt(key, "pair-1", "session_123", 2, edit)
        assertEquals(edit, DraftFrameCrypto.decrypt(key, "pair-1", "session_123", 2, 7, frame))
    }

    @Test fun pairSessionAndSequenceAreAuthenticated() {
        val frame = DraftFrameCrypto.encrypt(key, "pair-1", "session_123", 2, edit)
        assertThrows(Exception::class.java) {
            DraftFrameCrypto.decrypt(key, "pair-2", "session_123", 2, 7, frame)
        }
        assertThrows(Exception::class.java) {
            DraftFrameCrypto.decrypt(key, "pair-1", "session_123", 2, 8, frame)
        }
        assertThrows(Exception::class.java) {
            DraftFrameCrypto.decrypt(key, "pair-1", "session_456", 2, 7, frame)
        }
    }
}
