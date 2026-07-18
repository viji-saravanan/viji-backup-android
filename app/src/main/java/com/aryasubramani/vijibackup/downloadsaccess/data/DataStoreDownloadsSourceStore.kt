package com.aryasubramani.vijibackup.downloadsaccess.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

class DataStoreDownloadsSourceStore(
    private val dataStore: DataStore<Preferences>,
) : DownloadsSourceStore {
    override suspend fun read(): DownloadsSourceConfiguration {
        val preferences = dataStore.data.first()
        val configured = preferences[Keys.configured] == true
        val enabled = preferences[Keys.enabled] == true
        if (!configured) {
            if (preferences.contains(Keys.configured) || preferences.contains(Keys.enabled)) {
                clear()
            }
            return DownloadsSourceConfiguration()
        }
        return DownloadsSourceConfiguration(configured = true, enabled = enabled)
    }

    override suspend fun write(configuration: DownloadsSourceConfiguration) {
        if (!configuration.configured) {
            clear()
            return
        }
        dataStore.edit { preferences ->
            preferences[Keys.configured] = true
            preferences[Keys.enabled] = configuration.enabled
        }
    }

    private suspend fun clear() {
        dataStore.edit { preferences ->
            preferences.remove(Keys.configured)
            preferences.remove(Keys.enabled)
        }
    }

    private object Keys {
        val configured = booleanPreferencesKey("downloads_configured")
        val enabled = booleanPreferencesKey("downloads_enabled")
    }
}

internal fun downloadsSourceCorruptionHandler() =
    ReplaceFileCorruptionHandler<Preferences> { emptyPreferences() }

internal val Context.downloadsSourceDataStore by preferencesDataStore(
    name = "downloads_source",
    corruptionHandler = downloadsSourceCorruptionHandler(),
)
