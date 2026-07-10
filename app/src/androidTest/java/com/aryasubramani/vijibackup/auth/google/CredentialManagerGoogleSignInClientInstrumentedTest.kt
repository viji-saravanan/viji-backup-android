package com.aryasubramani.vijibackup.auth.google

import androidx.credentials.CredentialManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CredentialManagerGoogleSignInClientInstrumentedTest {
    @Test
    fun missingWebClientIdFailsBeforeCredentialUiIsOpened() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val client = CredentialManagerGoogleSignInClient(
            credentialManager = CredentialManager.create(context),
            webClientId = "",
        )

        val result = client.signIn(
            activityContext = context,
            mode = GoogleSignInMode.Explicit,
        )

        assertEquals(GoogleSignInResult.ConfigurationRequired, result)
    }
}
