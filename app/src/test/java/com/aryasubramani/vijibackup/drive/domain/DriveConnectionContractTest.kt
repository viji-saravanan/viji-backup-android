package com.aryasubramani.vijibackup.drive.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveConnectionContractTest {
    @Test
    fun onlyReadyIsAUsableDestination() {
        DriveConnectionResult.entries.forEach { result ->
            assertEquals(
                result == DriveConnectionResult.Ready,
                result.isReady,
            )
        }
    }

    @Test
    fun onlyTemporaryFailuresAreRetryableWithoutUserRepair() {
        DriveConnectionResult.entries.forEach { result ->
            assertEquals(
                result == DriveConnectionResult.TemporaryFailure,
                result.isAutomaticRetryCandidate,
            )
        }
    }

    @Test
    fun authorizationFailuresNeverClaimDestinationAccess() {
        assertFalse(DriveConnectionResult.NeedsAuthorization.isReady)
        assertFalse(DriveConnectionResult.AccountMismatch.isReady)
        assertFalse(DriveConnectionResult.ProviderUnavailable.isReady)
    }

    @Test
    fun driveScopeIsExactAndContainsNoWhitespace() {
        assertEquals(
            "https://www.googleapis.com/auth/drive",
            DRIVE_AUTHORIZATION_SCOPE,
        )
        assertTrue(DRIVE_AUTHORIZATION_SCOPE.none(Char::isWhitespace))
    }
}
