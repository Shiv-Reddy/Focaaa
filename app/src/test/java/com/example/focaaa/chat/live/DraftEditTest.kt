package com.example.focaaa.chat.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DraftEditTest {
    @Test fun insertsAtCursorAndReplaysExactly() {
        val edit = DraftEdit.between("helo", "hello", 3, 1000)
        assertEquals(3, edit.start)
        assertEquals("", edit.removed)
        assertEquals("l", edit.inserted)
        assertEquals("hello", edit.applyTo("helo"))
    }

    @Test fun deletionKeepsRemovedTextForTheConsentedHistory() {
        val edit = DraftEdit.between("secret note", "note", 4, 1200)
        assertEquals("secret ", edit.removed)
        assertEquals("", edit.inserted)
        assertEquals("note", edit.applyTo("secret note"))
    }

    @Test fun replacementAfterImeCompositionKeepsOrder() {
        val first = DraftEdit.between("I am gud", "I am good", 1, 100)
        val second = DraftEdit.between("I am good", "I am good!", 2, 101)
        assertEquals("I am good!", second.applyTo(first.applyTo("I am gud")))
        assertThrows(IllegalArgumentException::class.java) { first.applyTo("other") }
    }
}
