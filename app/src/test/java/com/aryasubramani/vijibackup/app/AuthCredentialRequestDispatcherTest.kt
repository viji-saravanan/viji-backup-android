package com.aryasubramani.vijibackup.app

import com.aryasubramani.vijibackup.auth.domain.GoogleAccount
import com.aryasubramani.vijibackup.auth.google.GoogleSignInMode
import com.aryasubramani.vijibackup.auth.google.GoogleSignInResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuthCredentialRequestDispatcherTest {
    @Test
    fun successfulRequestDeliversItsResultWithTheOriginalRequestId() = runTest {
        val deliveredResults = mutableListOf<Pair<Long, GoogleSignInResult>>()
        val dispatcher = AuthCredentialRequestDispatcher(
            coroutineScope = this,
            signIn = { GoogleSignInResult.Success(approvedAccount()) },
            onResult = { requestId, result -> deliveredResults += requestId to result },
            onInterrupted = {},
        )

        dispatcher.dispatch(requestId = 41L, mode = GoogleSignInMode.Explicit)
        advanceUntilIdle()

        assertEquals(
            listOf(41L to GoogleSignInResult.Success(approvedAccount())),
            deliveredResults,
        )
    }

    @Test
    fun secondRequestIsIgnoredWhileTheFirstCredentialRequestIsActive() = runTest {
        val signInStarted = CompletableDeferred<Unit>()
        val completeSignIn = CompletableDeferred<GoogleSignInResult>()
        val requestedModes = mutableListOf<GoogleSignInMode>()
        val deliveredRequestIds = mutableListOf<Long>()
        val dispatcher = AuthCredentialRequestDispatcher(
            coroutineScope = this,
            signIn = { mode ->
                requestedModes += mode
                signInStarted.complete(Unit)
                completeSignIn.await()
            },
            onResult = { requestId, _ -> deliveredRequestIds += requestId },
            onInterrupted = {},
        )

        dispatcher.dispatch(requestId = 1L, mode = GoogleSignInMode.Explicit)
        runCurrent()
        signInStarted.await()
        dispatcher.dispatch(requestId = 2L, mode = GoogleSignInMode.AuthorizedAccounts)
        runCurrent()

        assertEquals(listOf(GoogleSignInMode.Explicit), requestedModes)

        completeSignIn.complete(GoogleSignInResult.Cancelled)
        advanceUntilIdle()

        assertEquals(listOf(1L), deliveredRequestIds)
    }

    @Test
    fun lifecycleCancellationInterruptsTheActiveRequestWithoutDeliveringAResult() = runTest {
        val dispatcherScope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val signInStarted = CompletableDeferred<Unit>()
        val neverCompletes = CompletableDeferred<GoogleSignInResult>()
        val deliveredRequestIds = mutableListOf<Long>()
        val interruptedRequestIds = mutableListOf<Long>()
        val dispatcher = AuthCredentialRequestDispatcher(
            coroutineScope = dispatcherScope,
            signIn = {
                signInStarted.complete(Unit)
                neverCompletes.await()
            },
            onResult = { requestId, _ -> deliveredRequestIds += requestId },
            onInterrupted = { requestId -> interruptedRequestIds += requestId },
        )

        dispatcher.dispatch(requestId = 7L, mode = GoogleSignInMode.Explicit)
        runCurrent()
        signInStarted.await()
        dispatcherScope.cancel()
        runCurrent()

        assertEquals(emptyList<Long>(), deliveredRequestIds)
        assertEquals(listOf(7L), interruptedRequestIds)
    }
}

private fun approvedAccount() = requireNotNull(
    GoogleAccount.create(
        subject = "test-subject",
        email = "approved.user@example.test",
        displayName = "Approved User",
    ),
)
