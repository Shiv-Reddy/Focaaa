package com.example.focaaa.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MessageDeliveryStatusTest {
    @Test fun statusNeverMovesBackwardAfterLateRelayResponses() {
        assertEquals(MessageDeliveryStatus.SENT, MessageDeliveryStatus.QUEUED.advanceTo(1))
        assertEquals(MessageDeliveryStatus.DELIVERED, MessageDeliveryStatus.SENT.advanceTo(2))
        assertEquals(MessageDeliveryStatus.READ, MessageDeliveryStatus.DELIVERED.advanceTo(3))
        assertEquals(MessageDeliveryStatus.READ, MessageDeliveryStatus.READ.advanceTo(1))
    }

    @Test fun invalidStatusCannotBeDisplayedAsAReadReceipt() {
        assertThrows(IllegalArgumentException::class.java) { MessageDeliveryStatus.fromLevel(4) }
    }
}
