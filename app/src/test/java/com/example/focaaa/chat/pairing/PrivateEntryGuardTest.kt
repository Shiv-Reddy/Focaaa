package com.example.focaaa.chat.pairing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateEntryGuardTest {
    @Test
    fun pinSuccessIsIgnoredAfterLeavingUnlockOrBackgrounding() {
        assertTrue(canContinuePinGateAttempt(4, 4, isExpectedGateVisible = true))
        assertFalse(canContinuePinGateAttempt(4, 4, isExpectedGateVisible = false))
        assertFalse(canContinuePinGateAttempt(4, 5, isExpectedGateVisible = true))
    }
}
