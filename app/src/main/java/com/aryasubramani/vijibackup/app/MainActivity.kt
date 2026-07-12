package com.aryasubramani.vijibackup.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.aryasubramani.vijibackup.auth.presentation.AuthUiState
import com.aryasubramani.vijibackup.auth.presentation.AuthViewModel

class MainActivity : ComponentActivity() {
    private val appContainer: AppContainer
        get() = (application as VijiBackupApplication).appContainer

    private val authViewModel by viewModels<AuthViewModel> {
        AuthViewModel.Factory(
            sessionManager = appContainer.authSessionManager,
            isGoogleSignInConfigured = appContainer.isGoogleSignInConfigured,
        )
    }
    private val credentialRequestDispatcher by lazy(LazyThreadSafetyMode.NONE) {
        AuthCredentialRequestDispatcher(
            coroutineScope = lifecycleScope,
            signIn = { mode ->
                appContainer.googleSignInClient.signIn(
                    activityContext = this@MainActivity,
                    mode = mode,
                )
            },
            onResult = authViewModel::onSignInResult,
            onInterrupted = authViewModel::onSignInInterrupted,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val uiState by authViewModel.uiState.collectAsStateWithLifecycle()
            val signInRequest = (uiState as? AuthUiState.SigningIn)?.request

            LaunchedEffect(uiState) {
                if (uiState is AuthUiState.ReauthenticationRequired) {
                    authViewModel.startAutomaticReauthentication()
                }
            }
            LaunchedEffect(signInRequest?.id) {
                val request = signInRequest ?: return@LaunchedEffect
                val mode = authViewModel.consumeSignInRequest(request.id)
                    ?: return@LaunchedEffect
                credentialRequestDispatcher.dispatch(request.id, mode)
            }

            VijiBackupApp(
                uiState = uiState,
                onSignIn = authViewModel::signIn,
                onRetry = authViewModel::retry,
                onSignOut = authViewModel::signOut,
            )
        }
    }

    override fun onStart() {
        super.onStart()
        authViewModel.onAppForegrounded()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            authViewModel.onAppBackgrounded()
        }
    }
}
