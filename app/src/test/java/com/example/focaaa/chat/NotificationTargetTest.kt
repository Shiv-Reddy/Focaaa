package com.example.focaaa.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationTargetTest {
    private val alex = ConversationContact("pair_alex", "Alex")
    private val sam = ConversationContact("pair_sam", "Sam")

    @Test
    fun oneActiveContactCanBeOpenedWithoutAStoredChoice() {
        assertEquals(alex, chooseNotificationContact(listOf(alex), null))
        assertEquals(alex, chooseNotificationContact(listOf(alex), "previously_unpaired"))
    }

    @Test
    fun tileMustAskInsideNotificationsWhenMultipleContactsExist() {
        assertNull(tileContactWithoutChoice(emptyList()))
        assertEquals(alex, tileContactWithoutChoice(listOf(alex)))
        assertNull(tileContactWithoutChoice(listOf(alex, sam)))
    }

    @Test
    fun multipleContactsRequireAPreviouslySelectedActiveContact() {
        assertNull(chooseNotificationContact(listOf(alex, sam), null))
        assertEquals(sam, chooseNotificationContact(listOf(alex, sam), sam.conversationId))
        assertNull(chooseNotificationContact(listOf(alex, sam), "removed_pair"))
    }

    @Test
    fun inlineReplyNeverFallsBackToAnotherContact() {
        assertEquals(alex, boundNotificationContact(listOf(alex, sam), alex.conversationId, alex.conversationId))
        assertNull(boundNotificationContact(listOf(sam), alex.conversationId, sam.conversationId))
        assertNull(boundNotificationContact(listOf(sam), "", sam.conversationId))
        assertNull(boundNotificationContact(listOf(alex, sam), alex.conversationId, sam.conversationId))
    }
}
