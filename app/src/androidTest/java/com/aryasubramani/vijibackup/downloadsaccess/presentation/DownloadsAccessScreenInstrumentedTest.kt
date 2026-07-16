package com.aryasubramani.vijibackup.downloadsaccess.presentation

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aryasubramani.vijibackup.downloadsaccess.data.DownloadsSourceConfiguration
import com.aryasubramani.vijibackup.downloadsaccess.domain.DownloadsAccessHealth
import com.aryasubramani.vijibackup.downloadsaccess.domain.DownloadsAccessSnapshot
import com.aryasubramani.vijibackup.ui.theme.VijiBackupTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadsAccessScreenInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun unconfiguredDownloadsOffersThePermissionFlow() {
        var requested = false
        composeRule.setDownloadsContent(
            state = state(DownloadsAccessHealth.NotConfigured, configured = false),
            onRequestAccess = { requested = true },
        )

        composeRule.onNodeWithTag(DownloadsAccessTestTags.Screen).assertIsDisplayed()
        composeRule.onNodeWithTag(DownloadsAccessTestTags.Status).assertIsDisplayed()
        composeRule.onNodeWithTag(DownloadsAccessTestTags.PrimaryAction)
            .assertIsDisplayed()
            .assertHasClickAction()
            .assertIsEnabled()
            .performClick()

        assertTrue(requested)
    }

    @Test
    fun readyDownloadsExposesIndependentPauseManageAndConfirmedRemoveActions() {
        var enabled = true
        var permissionReviewRequested = false
        var removed = false
        composeRule.setDownloadsContent(
            state = state(DownloadsAccessHealth.Ready),
            onSetEnabled = { enabled = it },
            onReviewPermission = { permissionReviewRequested = true },
            onRemove = { removed = true },
        )

        composeRule.onNodeWithTag(DownloadsAccessTestTags.EnabledSwitch)
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithTag(DownloadsAccessTestTags.ManagePermission)
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithTag(DownloadsAccessTestTags.Remove)
            .assertIsDisplayed()
            .performClick()

        assertFalse(enabled)
        assertTrue(permissionReviewRequested)
        assertFalse(removed)
        composeRule.onNodeWithTag(DownloadsAccessTestTags.RemoveDialog).assertIsDisplayed()
        composeRule.onNodeWithTag(DownloadsAccessTestTags.ConfirmRemove).performClick()
        assertTrue(removed)
    }

    @Test
    fun legacyPlatformDelegatesDownloadsSelectionToTheExistingSafPicker() {
        var pickerRequested = false
        composeRule.setDownloadsContent(
            state = state(DownloadsAccessHealth.UseSafPicker, configured = false),
            onUseSafPicker = { pickerRequested = true },
        )

        composeRule.onNodeWithTag(DownloadsAccessTestTags.PrimaryAction)
            .assertIsDisplayed()
            .performClick()

        assertTrue(pickerRequested)
    }

    private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.setDownloadsContent(
        state: DownloadsAccessUiState,
        onRequestAccess: () -> Unit = {},
        onReviewPermission: () -> Unit = {},
        onSetEnabled: (Boolean) -> Unit = {},
        onRemove: () -> Unit = {},
        onUseSafPicker: () -> Unit = {},
    ) {
        setContent {
            VijiBackupTheme {
                DownloadsAccessContent(
                    uiState = state,
                    onRequestAccess = onRequestAccess,
                    onReviewPermission = onReviewPermission,
                    onSetEnabled = onSetEnabled,
                    onRemove = onRemove,
                    onUseSafPicker = onUseSafPicker,
                )
            }
        }
        waitForIdle()
    }
}

private fun state(
    health: DownloadsAccessHealth,
    configured: Boolean = true,
) = DownloadsAccessUiState(
    snapshot = DownloadsAccessSnapshot(
        configuration = DownloadsSourceConfiguration(
            configured = configured,
            enabled = health != DownloadsAccessHealth.Disabled,
        ),
        health = health,
    ),
    isLoading = false,
)
