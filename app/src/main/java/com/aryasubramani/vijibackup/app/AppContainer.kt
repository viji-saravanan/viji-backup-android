package com.aryasubramani.vijibackup.app

import android.content.Context
import androidx.credentials.CredentialManager
import com.aryasubramani.vijibackup.auth.config.GoogleSignInBuildConfiguration
import com.aryasubramani.vijibackup.auth.config.GoogleSignInConfiguration
import com.aryasubramani.vijibackup.auth.data.AuthSessionManager
import com.aryasubramani.vijibackup.auth.data.DataStoreAuthSessionStore
import com.aryasubramani.vijibackup.auth.data.authSessionDataStore
import com.aryasubramani.vijibackup.auth.domain.AccountAccessPolicy
import com.aryasubramani.vijibackup.auth.google.CredentialManagerCredentialStateClearer
import com.aryasubramani.vijibackup.auth.google.CredentialManagerGoogleSignInClient
import com.aryasubramani.vijibackup.auth.google.GoogleSignInClient

interface AppContainer {
    val authSessionManager: AuthSessionManager
    val googleSignInClient: GoogleSignInClient
    val isGoogleSignInConfigured: Boolean
}

internal class DefaultAppContainer(context: Context) : AppContainer {
    private val applicationContext = context.applicationContext
    private val credentialManager = CredentialManager.create(applicationContext)
    private val googleSignInConfiguration = GoogleSignInBuildConfiguration.value

    override val isGoogleSignInConfigured =
        googleSignInConfiguration is GoogleSignInConfiguration.Ready

    override val authSessionManager = AuthSessionManager(
        accessPolicy = AccountAccessPolicy(
            allowedEmails = when (val configuration = googleSignInConfiguration) {
                is GoogleSignInConfiguration.Ready -> configuration.allowedAccounts
                GoogleSignInConfiguration.Invalid -> emptySet()
            },
        ),
        sessionStore = DataStoreAuthSessionStore(applicationContext.authSessionDataStore),
        credentialStateClearer = CredentialManagerCredentialStateClearer(credentialManager),
    )

    override val googleSignInClient: GoogleSignInClient =
        CredentialManagerGoogleSignInClient(
            credentialManager = credentialManager,
            webClientId = when (val configuration = googleSignInConfiguration) {
                is GoogleSignInConfiguration.Ready -> configuration.webClientId
                GoogleSignInConfiguration.Invalid -> ""
            },
        )
}
