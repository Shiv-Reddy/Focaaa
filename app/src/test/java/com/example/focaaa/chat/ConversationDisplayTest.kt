package com.example.focaaa.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConversationDisplayTest {
    @Test
    fun contactNameIsTrimmedAndBoundedToFortyCharacters() {
        assertEquals("Sam", normalizeContactName("  Sam  "))
        assertNull(normalizeContactName("   "))
        assertNull(normalizeContactName("x".repeat(41)))
        assertEquals("x".repeat(40), normalizeContactName("x".repeat(40)))
    }

    @Test
    fun defaultDatabaseTitleDisplaysAsPrivateContact() {
        assertEquals("Private contact", contactDisplayName(null))
        assertEquals("Private contact", contactDisplayName("Partner"))
        assertEquals("Alex", contactDisplayName("Alex"))
    }

    @Test
    fun avatarInitialsUseAtMostTwoNameParts() {
        assertEquals("PC", contactInitials("Private contact"))
        assertEquals("A", contactInitials("Alex"))
    }
}
