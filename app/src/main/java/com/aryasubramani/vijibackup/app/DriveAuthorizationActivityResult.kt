package com.aryasubramani.vijibackup.app

import android.app.Activity
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContracts

internal sealed interface DriveAuthorizationActivityOutcome {
    class Complete(val data: Intent) : DriveAuthorizationActivityOutcome {
        override fun toString(): String = "DriveAuthorizationActivityOutcome.Complete(data=REDACTED)"
    }

    data object Cancelled : DriveAuthorizationActivityOutcome

    data object LaunchFailed : DriveAuthorizationActivityOutcome

    data object Invalid : DriveAuthorizationActivityOutcome
}

internal fun classifyDriveAuthorizationActivityResult(
    resultCode: Int,
    data: Intent?,
): DriveAuthorizationActivityOutcome {
    val launchFailed =
        resultCode == Activity.RESULT_CANCELED &&
            data?.action ==
            ActivityResultContracts.StartIntentSenderForResult.ACTION_INTENT_SENDER_REQUEST &&
            data.hasExtra(
                ActivityResultContracts.StartIntentSenderForResult.EXTRA_SEND_INTENT_EXCEPTION,
            )
    return when {
        launchFailed -> DriveAuthorizationActivityOutcome.LaunchFailed
        resultCode == Activity.RESULT_CANCELED -> DriveAuthorizationActivityOutcome.Cancelled
        resultCode == Activity.RESULT_OK && data != null ->
            DriveAuthorizationActivityOutcome.Complete(data)
        else -> DriveAuthorizationActivityOutcome.Invalid
    }
}
