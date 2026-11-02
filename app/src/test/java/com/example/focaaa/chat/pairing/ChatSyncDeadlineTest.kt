package com.example.focaaa.chat.pairing

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSyncDeadlineTest {
    @Test fun relayTimeoutIsRecoverableWithoutCancellingThePollingLoop() = runBlocking {
        try {
            boundedChatSync(25) { awaitCancellation() }
            error("Expected a deadline error")
        } catch (error: Exception) {
            assertTrue(error is ChatSyncDeadlineExceeded)
            assertFalse(error is CancellationException)
            assertTrue(error.message!!.contains("timed out"))
        }
    }
}
