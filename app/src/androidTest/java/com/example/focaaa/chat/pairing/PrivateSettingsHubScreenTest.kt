package com.example.focaaa.chat.pairing

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Rule
import org.junit.Test

class PrivateSettingsHubScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun settingsHubGroupsActionsUnderClearSections() {
        compose.setContent {
            MaterialTheme {
                PrivateSettingsHubScreen(
                    privateEntryMinutes = 3,
                    hasActivePartner = false,
                    onPrivateEntryMinutesChange = {},
                    onContacts = {},
                    onCreateInvite = {},
                    onJoinInvite = {},
                    onAddTile = {},
                    onChangePin = {},
                    onReviewPrivacy = {},
                    onBackupHistory = {},
                    onRestoreHistory = {},
                    onBack = {}
                )
            }
        }

        compose.onNodeWithText("Partner").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Add partner").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Create invite").assertIsDisplayed()
        compose.onNodeWithText("Join invite").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Private entry").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("10 min").assertIsDisplayed()
        compose.onNodeWithText("Tools").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Back up history").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Restore history").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Privacy & how it works").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun connectedPartnerDoesNotOfferAnotherInvite() {
        compose.setContent {
            MaterialTheme {
                PrivateSettingsHubScreen(
                    privateEntryMinutes = 3,
                    hasActivePartner = true,
                    onPrivateEntryMinutesChange = {},
                    onContacts = {},
                    onCreateInvite = {},
                    onJoinInvite = {},
                    onAddTile = {},
                    onChangePin = {},
                    onReviewPrivacy = {},
                    onBackupHistory = {},
                    onRestoreHistory = {},
                    onBack = {}
                )
            }
        }

        compose.onNodeWithText("Partner connected").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Add partner").assertDoesNotExist()
    }
}
