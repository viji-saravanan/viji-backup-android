package com.aryasubramani.vijibackup.drive.google

import android.content.Intent
import com.aryasubramani.vijibackup.auth.domain.GoogleAccount
import com.aryasubramani.vijibackup.drive.domain.DriveConnectionResult
import com.aryasubramani.vijibackup.drive.network.DriveDestinationHealthProbe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DriveConnectionCoordinatorTest {
    private val account = requireNotNull(
        GoogleAccount.create(
            subject = "approved-subject",
            email = "approved@example.test",
            displayName = "Approved User",
        ),
    )

    @Test
    fun authorizedStartImmediatelyChecksDestinationHealth() = runTest {
        val token = token()
        var observedToken: DriveAccessToken? = null
        val coordinator = DriveConnectionCoordinator(
            authorizationProvider = provider(
                start = DriveAuthorizationStart.Authorized(token),
            ),
            destinationProbe = DriveDestinationHealthProbe { accessToken ->
                observedToken = accessToken
                DriveConnectionResult.Ready
            },
        )

        assertEquals(
            DriveConnectionStep.Complete(DriveConnectionResult.Ready),
            coordinator.begin(account),
        )
        assertEquals(token, observedToken)
    }

    @Test
    fun authorizationFailureNeverCallsDestination() = runTest {
        var destinationCalls = 0
        val coordinator = DriveConnectionCoordinator(
            authorizationProvider = provider(
                start = DriveAuthorizationStart.Failed(
                    DriveConnectionResult.ProviderUnavailable,
                ),
            ),
            destinationProbe = DriveDestinationHealthProbe {
                destinationCalls += 1
                DriveConnectionResult.Ready
            },
        )

        assertEquals(
            DriveConnectionStep.Complete(DriveConnectionResult.ProviderUnavailable),
            coordinator.begin(account),
        )
        assertEquals(0, destinationCalls)
    }

    @Test
    fun completedConsentConsumesTokenWithoutPersistingItInTheCoordinator() = runTest {
        val token = token()
        var completeCalls = 0
        val coordinator = DriveConnectionCoordinator(
            authorizationProvider = object : DriveAuthorizationProvider {
                override suspend fun begin(account: GoogleAccount): DriveAuthorizationStart =
                    error("not used")

                override fun complete(
                    expectedAccount: GoogleAccount,
                    data: Intent?,
                ): DriveAuthorizationEvaluation {
                    completeCalls += 1
                    return DriveAuthorizationEvaluation.Authorized(token)
                }
            },
            destinationProbe = DriveDestinationHealthProbe { DriveConnectionResult.Ready },
        )

        assertEquals(
            DriveConnectionResult.Ready,
            coordinator.complete(account, data = null),
        )
        assertEquals(1, completeCalls)
    }

    @Test
    fun failedConsentResultNeverCallsDestination() = runTest {
        var destinationCalls = 0
        val coordinator = DriveConnectionCoordinator(
            authorizationProvider = object : DriveAuthorizationProvider {
                override suspend fun begin(account: GoogleAccount): DriveAuthorizationStart =
                    error("not used")

                override fun complete(
                    expectedAccount: GoogleAccount,
                    data: Intent?,
                ) = DriveAuthorizationEvaluation.Failed(
                    DriveConnectionResult.InvalidResponse,
                )
            },
            destinationProbe = DriveDestinationHealthProbe {
                destinationCalls += 1
                DriveConnectionResult.Ready
            },
        )

        assertEquals(
            DriveConnectionResult.InvalidResponse,
            coordinator.complete(account, data = null),
        )
        assertEquals(0, destinationCalls)
    }

    @Test
    fun cancellationFromAuthorizationIsNotConverted() {
        val coordinator = DriveConnectionCoordinator(
            authorizationProvider = object : DriveAuthorizationProvider {
                override suspend fun begin(account: GoogleAccount): DriveAuthorizationStart {
                    throw CancellationException("cancelled")
                }

                override fun complete(
                    expectedAccount: GoogleAccount,
                    data: Intent?,
                ): DriveAuthorizationEvaluation = error("not used")
            },
            destinationProbe = DriveDestinationHealthProbe { DriveConnectionResult.Ready },
        )

        assertThrows(CancellationException::class.java) {
            kotlinx.coroutines.runBlocking { coordinator.begin(account) }
        }
    }

    private fun provider(start: DriveAuthorizationStart): DriveAuthorizationProvider =
        object : DriveAuthorizationProvider {
            override suspend fun begin(account: GoogleAccount): DriveAuthorizationStart = start

            override fun complete(
                expectedAccount: GoogleAccount,
                data: Intent?,
            ): DriveAuthorizationEvaluation = error("not used")
        }

    private fun token(): DriveAccessToken =
        requireNotNull(DriveAccessToken.create("ephemeral-token"))
}
