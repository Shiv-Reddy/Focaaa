package com.example.focaaa.chat.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveDraftEngineTest {
    @Test fun composerIsNotCapturedUntilSharingIsEnabledAndStopsImmediately() {
        val engine = LiveDraftEngine()
        assertNull(engine.textChanged("private", 100))
        val initial = engine.start("session_123", "private", 101)
        assertEquals("private", initial?.inserted)
        assertEquals(" ", engine.textChanged("private ", 102)?.inserted)
        engine.stop()
        assertNull(engine.textChanged("private again", 103))
    }
}
