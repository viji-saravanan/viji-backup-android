package com.aryasubramani.vijibackup.auth.google

import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import com.aryasubramani.vijibackup.auth.data.CredentialStateClearer

class CredentialManagerCredentialStateClearer(
    private val credentialManager: CredentialManager,
) : CredentialStateClearer {
    override suspend fun clear() {
        credentialManager.clearCredentialState(ClearCredentialStateRequest())
    }
}
