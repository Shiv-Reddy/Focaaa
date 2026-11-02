package com.example.focaaa.chat.backup

import com.example.focaaa.chat.MessageSender
import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HistoryBackupCodecTest {
    private val snapshot = HistorySnapshot(
        backupId = "f7a826f742bd4413a6bc5c0da3f8c109",
        createdAtMillis = 1_800_000_000_000,
        conversations = listOf(HistoryConversation(
            sourceId = "pair-1",
            title = "Sam",
            messages = listOf(
                HistoryMessage("msg-1", MessageSender.LOCAL_USER, 1_800_000_000_001, "Hello"),
                HistoryMessage("msg-2", MessageSender.REMOTE_PARTNER, 1_800_000_000_002, "Hi 👋")
            )
        ))
    )

    @Test fun roundTripUsesASeparateRandomRecoveryKey() {
        val key = HistoryBackupCodec.generateRecoveryKey()
        assertEquals(43, key.length)
        assertNotEquals(key, HistoryBackupCodec.generateRecoveryKey())
        val file = HistoryBackupCodec.encrypt(snapshot, key)
        assertEquals(snapshot, HistoryBackupCodec.decrypt(file, key))
        assertFalse(file.toString(Charsets.ISO_8859_1).contains("Hello"))
        assertFalse(file.toString(Charsets.ISO_8859_1).contains("Sam"))
    }

    @Test fun wrongKeyTamperTruncationAndTrailingBytesFail() {
        val key = HistoryBackupCodec.generateRecoveryKey()
        val file = HistoryBackupCodec.encrypt(snapshot, key)
        assertThrows(Exception::class.java) {
            HistoryBackupCodec.decrypt(file, HistoryBackupCodec.generateRecoveryKey())
        }
        assertThrows(Exception::class.java) {
            HistoryBackupCodec.decrypt(file.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() }, key)
        }
        assertThrows(Exception::class.java) { HistoryBackupCodec.decrypt(file.copyOf(file.size - 1), key) }
        assertThrows(Exception::class.java) { HistoryBackupCodec.decrypt(file + byteArrayOf(1), key) }
    }

    @Test fun unknownVersionAndInvalidKeyFail() {
        val key = HistoryBackupCodec.generateRecoveryKey()
        val file = HistoryBackupCodec.encrypt(snapshot, key)
        assertThrows(Exception::class.java) {
            HistoryBackupCodec.decrypt(file.copyOf().also { it[8] = 2 }, key)
        }
        assertThrows(Exception::class.java) { HistoryBackupCodec.decrypt(file, "short") }
    }

    @Test fun eachExportHasAFreshNonceAndFileReadIsBounded() {
        val key = HistoryBackupCodec.generateRecoveryKey()
        val first = HistoryBackupCodec.encrypt(snapshot, key)
        val second = HistoryBackupCodec.encrypt(snapshot, key)
        assertFalse(first.contentEquals(second))
        assertEquals(snapshot, HistoryBackupCodec.decrypt(second, key))
        assertEquals(first.toList(), HistoryBackupCodec.readBounded(ByteArrayInputStream(first)).toList())
        assertThrows(Exception::class.java) {
            HistoryBackupCodec.readBounded(ByteArrayInputStream(ByteArray(HistoryBackupCodec.MAX_FILE_BYTES + 1)))
        }
    }

    @Test fun rejectsOversizedAndDemoHistoryBeforeWriting() {
        val key = HistoryBackupCodec.generateRecoveryKey()
        val tooLarge = snapshot.copy(conversations = listOf(snapshot.conversations[0].copy(
            messages = listOf(HistoryMessage("id", MessageSender.LOCAL_USER, 1, "x".repeat(4001)))
        )))
        assertThrows(Exception::class.java) { HistoryBackupCodec.encrypt(tooLarge, key) }
        val demo = snapshot.copy(conversations = listOf(snapshot.conversations[0].copy(
            messages = listOf(HistoryMessage("id", MessageSender.DEMO_PARTNER, 1, "demo"))
        )))
        assertThrows(Exception::class.java) { HistoryBackupCodec.encrypt(demo, key) }
    }
}
