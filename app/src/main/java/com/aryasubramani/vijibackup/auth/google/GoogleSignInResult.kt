package com.aryasubramani.vijibackup.auth.google

import com.aryasubramani.vijibackup.auth.domain.GoogleAccount

enum class GoogleSignInMode {
    AuthorizedAccounts,
    Explicit,
}

sealed interface GoogleSignInResult {
    data class Success(val account: GoogleAccount) : GoogleSignInResult

    data object Cancelled : GoogleSignInResult

    data object NoCredential : GoogleSignInResult

    data object ConfigurationRequired : GoogleSignInResult

    data object ProviderUnavailable : GoogleSignInResult

    data object Interrupted : GoogleSignInResult

    data object InvalidCredential : GoogleSignInResult

    data object UnknownFailure : GoogleSignInResult
}
