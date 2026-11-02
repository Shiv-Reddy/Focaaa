package com.example.focaaa.chat.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HistoryTransferFeedbackTest {
    @Test fun pickerFeedbackOnTheFocusHomeDoesNotRevealThePrivateArea() {
        val expected = mapOf(
            HistoryTransferOutcome.SAVED to "File saved.",
            HistoryTransferOutcome.SAVE_FAILED to "Could not save file.",
            HistoryTransferOutcome.IMPORTED to "File imported.",
            HistoryTransferOutcome.IMPORT_FAILED to "Could not import file.",
            HistoryTransferOutcome.INTERRUPTED to "File operation interrupted."
        )
        expected.forEach { (outcome, message) ->
            assertEquals(message, publicHistoryTransferToast(outcome))
            assertFalse(message.contains(Regex("chat|message|private|pair|history", RegexOption.IGNORE_CASE)))
        }
    }
}
