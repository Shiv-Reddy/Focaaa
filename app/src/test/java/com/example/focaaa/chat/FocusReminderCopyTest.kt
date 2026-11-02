package com.example.focaaa.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusReminderCopyTest {
    @Test
    fun incomingHintCopyIsVariedAndContainsOnlyFocusLanguage() {
        val prompts = (0 until FocusReminderCopy.count).map(FocusReminderCopy::at)
        val titles = (0 until FocusReminderCopy.count).map(FocusReminderCopy::titleAt)
        assertTrue("Use a varied set of focus prompts", prompts.distinct().size >= 10)
        assertTrue("Vary the title as well as the body", titles.distinct().size >= 8)
        val privateWords = Regex("message|chat|partner|reply|conversation|invite|pair", RegexOption.IGNORE_CASE)
        (prompts + titles).forEach { prompt ->
            assertFalse("A prompt disclosed private activity: $prompt", privateWords.containsMatchIn(prompt))
            assertTrue(prompt.isNotBlank())
        }
        assertEquals(prompts.first(), FocusReminderCopy.at(FocusReminderCopy.count))
    }

    @Test fun nextPromptNeverRepeatsThePreviousOne() {
        for (previous in 0 until FocusReminderCopy.count) {
            for (randomIndex in 0 until FocusReminderCopy.count) {
                assertFalse(previous == FocusReminderCopy.nextIndex(previous, randomIndex))
            }
        }
    }
}
