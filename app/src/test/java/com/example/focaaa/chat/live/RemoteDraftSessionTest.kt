package com.example.focaaa.chat.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteDraftSessionTest {
    @Test fun duplicateAndGapCannotCorruptTheVisibleDraft() {
        val session = RemoteDraftSession("session_123")
        val first = DraftEdit.between("", "Hello", 1, 100)
        assertTrue(session.apply(first))
        assertEquals("Hello", session.text)
        assertFalse(session.apply(first))
        assertEquals("Hello", session.text)
        assertThrows(IllegalArgumentException::class.java) {
            session.apply(DraftEdit.between("Hello", "Hello!", 3, 101))
        }
        assertEquals("Hello", session.text)
    }
}
