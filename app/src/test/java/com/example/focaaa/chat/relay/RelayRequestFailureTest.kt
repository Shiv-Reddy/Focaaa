package com.example.focaaa.chat.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayRequestFailureTest {
    @Test fun onlyExplicitPairRevocationEndsTheChat() {
        val ended = relayRequestFailure(410, "pair-revoked", "This private connection has ended.")
        assertTrue(ended is PairEndedException)
        assertEquals("This private connection has ended.", ended.message)

        assertTrue(relayRequestFailure(403, "permission-denied", "Denied") !is PairEndedException)
        assertTrue(relayRequestFailure(412, "failed-precondition", "Pending") !is PairEndedException)
        assertTrue(relayRequestFailure(410, "other", "Gone") !is PairEndedException)
    }
}
