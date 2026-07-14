package com.aryasubramani.vijibackup.auth.data

import com.aryasubramani.vijibackup.auth.domain.AccountAccess
import com.aryasubramani.vijibackup.auth.domain.AccountAccessPolicy
import com.aryasubramani.vijibackup.auth.domain.GoogleAccount
import kotlinx.coroutines.CancellationException

class AuthSessionManager(
    private val accessPolicy: AccountAccessPolicy,
    private val sessionStore: AuthSessionStore,
    private val credentialStateClearer: CredentialStateClearer,
) {
    suspend fun loadCachedSession(): LoadAuthSessionResult {
        val cleanupPending = captureFailure { sessionStore.isProviderCleanupPending() }
            .getOrElse { return LoadAuthSessionResult.PersistenceFailure }
        val providerStateCleared = if (cleanupPending) {
            clearProviderStateAndCompleteMarker()
        } else {
            true
        }
        val account = captureFailure { sessionStore.read() }
            .getOrElse { return LoadAuthSessionResult.PersistenceFailure }

        return if (account == null) {
            LoadAuthSessionResult.SignedOut(providerStateCleared)
        } else {
            LoadAuthSessionResult.ReauthenticationRequired(account)
        }
    }

    suspend fun authorize(account: GoogleAccount): AuthorizeAccountResult =
        when (val access = accessPolicy.evaluate(account)) {
            is AccountAccess.Approved -> {
                captureFailure { sessionStore.save(access.account) }
                    .fold(
                        onSuccess = { AuthorizeAccountResult.Approved(access.account) },
                        onFailure = { AuthorizeAccountResult.PersistenceFailure },
                    )
            }

            is AccountAccess.Blocked -> {
                val localStateCleared = captureFailure {
                    sessionStore.beginProviderCleanup()
                }.isSuccess
                val providerStateCleared = if (localStateCleared) {
                    clearProviderStateAndCompleteMarker()
                } else {
                    captureFailure { credentialStateClearer.clear() }.isSuccess
                }
                AuthorizeAccountResult.Blocked(
                    account = access.account,
                    localStateCleared = localStateCleared,
                    providerStateCleared = providerStateCleared,
                )
            }
        }

    suspend fun signOut(): SignOutResult {
        if (captureFailure { sessionStore.beginProviderCleanup() }.isFailure) {
            return SignOutResult.PersistenceFailure
        }

        return SignOutResult.SignedOut(
            providerStateCleared = clearProviderStateAndCompleteMarker(),
        )
    }

    private suspend fun clearProviderStateAndCompleteMarker(): Boolean {
        if (captureFailure { credentialStateClearer.clear() }.isFailure) return false
        return captureFailure { sessionStore.completeProviderCleanup() }.isSuccess
    }
}

private suspend fun <T> captureFailure(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Exception) {
        Result.failure(failure)
    }

sealed interface LoadAuthSessionResult {
    data class SignedOut(val providerStateCleared: Boolean) : LoadAuthSessionResult

    data class ReauthenticationRequired(val account: GoogleAccount) : LoadAuthSessionResult

    data object PersistenceFailure : LoadAuthSessionResult
}

sealed interface AuthorizeAccountResult {
    data class Approved(val account: GoogleAccount) : AuthorizeAccountResult

    data class Blocked(
        val account: GoogleAccount,
        val localStateCleared: Boolean,
        val providerStateCleared: Boolean,
    ) : AuthorizeAccountResult

    data object PersistenceFailure : AuthorizeAccountResult
}

sealed interface SignOutResult {
    data class SignedOut(val providerStateCleared: Boolean) : SignOutResult

    data object PersistenceFailure : SignOutResult
}
