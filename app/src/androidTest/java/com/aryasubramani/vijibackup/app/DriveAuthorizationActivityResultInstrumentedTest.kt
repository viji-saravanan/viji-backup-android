package com.aryasubramani.vijibackup.app

import android.app.Activity
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DriveAuthorizationActivityResultInstrumentedTest {
    @Test
    fun ordinaryCancellationRemainsAUserCancellation() {
        assertTrue(
            classifyDriveAuthorizationActivityResult(
                resultCode = Activity.RESULT_CANCELED,
                data = null,
            ) is DriveAuthorizationActivityOutcome.Cancelled,
        )
    }

    @Test
    fun synthesizedIntentSenderFailureIsNotMisreportedAsUserCancellation() {
        val data = Intent(
            ActivityResultContracts.StartIntentSenderForResult.ACTION_INTENT_SENDER_REQUEST,
        ).putExtra(
            ActivityResultContracts.StartIntentSenderForResult.EXTRA_SEND_INTENT_EXCEPTION,
            true,
        )

        assertTrue(
            classifyDriveAuthorizationActivityResult(
                resultCode = Activity.RESULT_CANCELED,
                data = data,
            ) is DriveAuthorizationActivityOutcome.LaunchFailed,
        )
    }

    @Test
    fun successfulResultCarriesTheUnmodifiedEphemeralIntent() {
        val data = Intent("provider-result")

        val outcome = classifyDriveAuthorizationActivityResult(
            resultCode = Activity.RESULT_OK,
            data = data,
        )

        assertTrue(outcome is DriveAuthorizationActivityOutcome.Complete)
        assertSame(data, (outcome as DriveAuthorizationActivityOutcome.Complete).data)
    }

    @Test
    fun missingSuccessfulDataAndUnknownResultCodesAreInvalid() {
        assertTrue(
            classifyDriveAuthorizationActivityResult(
                resultCode = Activity.RESULT_OK,
                data = null,
            ) is DriveAuthorizationActivityOutcome.Invalid,
        )
        assertTrue(
            classifyDriveAuthorizationActivityResult(
                resultCode = 42,
                data = Intent(),
            ) is DriveAuthorizationActivityOutcome.Invalid,
        )
    }
}
