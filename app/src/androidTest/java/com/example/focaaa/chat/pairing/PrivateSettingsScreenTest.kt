package com.example.focaaa.chat.pairing

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PrivateSettingsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun lowerManagementActionsCanBeScrolledIntoViewAndOpened() {
        var changePinRequested = false
        var privacyReviewRequested = false
        compose.setContent {
            MaterialTheme {
                PrivateSettingsScreen(
                    privateEntryMinutes = 3,
                    contactName = "A contact",
                    onPrivateEntryMinutesChange = {},
                    onSaveContactName = {},
                    onChangePin = { changePinRequested = true },
                    onReviewPrivacyLimits = { privacyReviewRequested = true },
                    onBack = {}
                )
            }
        }

        compose.onNodeWithText("Change access PIN").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Review privacy limits").performScrollTo().assertIsDisplayed().performClick()

        assertTrue(changePinRequested)
        assertTrue(privacyReviewRequested)
    }
}
