package com.aryasubramani.vijibackup.auth.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aryasubramani.vijibackup.auth.domain.GoogleAccount
import kotlinx.coroutines.flow.first

class DataStoreAuthSessionStore(
    private val dataStore: DataStore<Preferences>,
) : AuthSessionStore {
    override suspend fun read(): GoogleAccount? {
        val preferences = dataStore.data.first()
        val subject = preferences[Keys.subject]
        val email = preferences[Keys.email]
        val displayName = preferences[Keys.displayName]

        if (subject == null && email == null && displayName == null) return null

        val account = GoogleAccount.create(
            subject = subject.orEmpty(),
            email = email.orEmpty(),
            displayName = displayName,
        )
        if (account != null) return account

        clearAccountMetadata()
        return null
    }

    override suspend fun save(account: GoogleAccount) {
        dataStore.edit { preferences ->
            preferences.remove(Keys.providerCleanupPending)
            preferences[Keys.subject] = account.subject
            preferences[Keys.email] = account.email
            if (account.displayName == null) {
                preferences.remove(Keys.displayName)
            } else {
                preferences[Keys.displayName] = account.displayName
            }
        }
    }

    override suspend fun clear() {
        dataStore.edit { preferences -> preferences.clear() }
    }

    override suspend fun beginProviderCleanup() {
        dataStore.edit { preferences ->
            preferences.clear()
            preferences[Keys.providerCleanupPending] = true
        }
    }

    override suspend fun isProviderCleanupPending(): Boolean =
        dataStore.data.first()[Keys.providerCleanupPending] == true

    override suspend fun completeProviderCleanup() {
        dataStore.edit { preferences -> preferences.remove(Keys.providerCleanupPending) }
    }

    private suspend fun clearAccountMetadata() {
        dataStore.edit { preferences ->
            preferences.remove(Keys.subject)
            preferences.remove(Keys.email)
            preferences.remove(Keys.displayName)
        }
    }

    private object Keys {
        val subject = stringPreferencesKey("google_account_subject")
        val email = stringPreferencesKey("google_account_email")
        val displayName = stringPreferencesKey("google_account_display_name")
        val providerCleanupPending = booleanPreferencesKey("provider_cleanup_pending")
    }
}

internal fun authSessionCorruptionHandler() =
    ReplaceFileCorruptionHandler<Preferences> { emptyPreferences() }

internal val Context.authSessionDataStore by preferencesDataStore(
    name = "auth_session",
    corruptionHandler = authSessionCorruptionHandler(),
)
