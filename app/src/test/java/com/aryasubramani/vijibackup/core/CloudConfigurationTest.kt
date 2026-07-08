package com.aryasubramani.vijibackup.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudConfigurationTest {
    @Test
    fun allowedGoogleAccountsMatchConfirmedAccessList() {
        assertEquals(
            setOf(
                "owner.primary@example.test",
                "owner.alternate@example.test",
                "primary.user@example.test",
                "alternate.user@example.test",
            ),
            CloudConfiguration.allowedGoogleAccounts,
        )
    }

    @Test
    fun allowlistCheckNormalizesEmailInput() {
        assertTrue(CloudConfiguration.isAllowedGoogleAccount("  PRIMARY.USER@example.test "))
        assertFalse(CloudConfiguration.isAllowedGoogleAccount(""))
        assertFalse(CloudConfiguration.isAllowedGoogleAccount("someone@example.com"))
    }

    @Test
    fun cloudValuesUseConfirmedDriveDestinationAndScope() {
        assertEquals("https://www.googleapis.com/auth/drive.file", CloudConfiguration.driveFileScope)
        assertEquals("<private-drive-upload-folder-id>", CloudConfiguration.driveUploadFolderId)
        assertEquals("owner.primary@example.test", CloudConfiguration.driveUploadFolderOwnerEmail)
    }

    @Test
    fun androidOAuthClientIdsAreMappedByApplicationId() {
        assertEquals(
            CloudConfiguration.internalDebugAndroidOAuthClientId,
            CloudConfiguration.androidOAuthClientIdForApplicationId(AppIdentity.internalApplicationId),
        )
        assertEquals(
            CloudConfiguration.publicDebugAndroidOAuthClientId,
            CloudConfiguration.androidOAuthClientIdForApplicationId(AppIdentity.baseApplicationId),
        )
        assertNull(CloudConfiguration.androidOAuthClientIdForApplicationId("com.example.unknown"))
    }

    @Test
    fun completionEmailsGoToAllConfirmedAllowedAccountsForNow() {
        assertEquals(CloudConfiguration.allowedGoogleAccounts, CloudConfiguration.completionEmailRecipients)
        assertEquals("owner.primary@example.test", CloudConfiguration.emailSender)
        assertEquals("google-apps-script-mailapp-relay", CloudConfiguration.emailMethod)
    }
}
