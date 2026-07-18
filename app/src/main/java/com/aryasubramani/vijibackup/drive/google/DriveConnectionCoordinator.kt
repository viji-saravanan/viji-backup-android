package com.aryasubramani.vijibackup.drive.google

import android.app.PendingIntent
import android.content.Intent
import com.aryasubramani.vijibackup.auth.domain.GoogleAccount
import com.aryasubramani.vijibackup.drive.domain.DriveConnectionResult
import com.aryasubramani.vijibackup.drive.network.DriveDestinationHealthProbe

internal sealed interface DriveAuthorizationStart {
    data class Authorized(val accessToken: DriveAccessToken) : DriveAuthorizationStart

    data class ResolutionRequired(val pendingIntent: PendingIntent) : DriveAuthorizationStart

    data class Failed(val result: DriveConnectionResult) : DriveAuthorizationStart
}

internal interface DriveAuthorizationProvider {
    suspend fun begin(account: GoogleAccount): DriveAuthorizationStart

    fun complete(
        expectedAccount: GoogleAccount,
        data: Intent?,
    ): DriveAuthorizationEvaluation
}

internal sealed interface DriveConnectionStep {
    data class Complete(val result: DriveConnectionResult) : DriveConnectionStep

    data class ResolutionRequired(val pendingIntent: PendingIntent) : DriveConnectionStep
}

internal class DriveConnectionCoordinator(
    private val authorizationProvider: DriveAuthorizationProvider,
    private val destinationProbe: DriveDestinationHealthProbe,
) {
    suspend fun begin(account: GoogleAccount): DriveConnectionStep =
        when (val authorization = authorizationProvider.begin(account)) {
            is DriveAuthorizationStart.Authorized -> DriveConnectionStep.Complete(
                destinationProbe.check(authorization.accessToken),
            )
            is DriveAuthorizationStart.ResolutionRequired ->
                DriveConnectionStep.ResolutionRequired(authorization.pendingIntent)
            is DriveAuthorizationStart.Failed ->
                DriveConnectionStep.Complete(authorization.result)
        }

    suspend fun complete(
        expectedAccount: GoogleAccount,
        data: Intent?,
    ): DriveConnectionResult =
        when (
            val authorization = authorizationProvider.complete(
                expectedAccount = expectedAccount,
                data = data,
            )
        ) {
            is DriveAuthorizationEvaluation.Authorized ->
                destinationProbe.check(authorization.accessToken)
            is DriveAuthorizationEvaluation.Failed -> authorization.result
        }
}
