package com.example.focaaa.focus

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateSettingsShortcutTest {
    @Test fun exactTrimmedSettingsWordIsReserved() {
        assertTrue(isPrivateSettingsShortcut(" settings "))
        assertTrue(isPrivateSettingsShortcut("SETTINGS"))
        assertFalse(isPrivateSettingsShortcut("check settings"))
        assertFalse(isPrivateSettingsShortcut("settings please"))
    }
}
