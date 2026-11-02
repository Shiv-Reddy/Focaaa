package com.example.focaaa.chat

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisibleChatTrackerTest {
    @After fun reset() {
        VisibleChatTracker.leave("pair-a")
        VisibleChatTracker.leave("pair-b")
    }

    @Test fun exactVisibleChatSuppressesOnlyItsOwnAlert() {
        assertTrue(VisibleChatTracker.shouldAlert("pair-a"))
        VisibleChatTracker.enter("pair-a")
        assertFalse(VisibleChatTracker.shouldAlert("pair-a"))
        assertTrue(VisibleChatTracker.shouldAlert("pair-b"))
        VisibleChatTracker.leave("pair-a")
        assertTrue(VisibleChatTracker.shouldAlert("pair-a"))
    }

    @Test fun staleScreenDisposalCannotClearNewlyVisibleChat() {
        VisibleChatTracker.enter("pair-a")
        VisibleChatTracker.enter("pair-b")
        VisibleChatTracker.leave("pair-a")
        assertFalse(VisibleChatTracker.shouldAlert("pair-b"))
    }
}
