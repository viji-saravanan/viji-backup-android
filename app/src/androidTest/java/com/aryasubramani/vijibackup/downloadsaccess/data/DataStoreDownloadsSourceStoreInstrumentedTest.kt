package com.aryasubramani.vijibackup.downloadsaccess.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DataStoreDownloadsSourceStoreInstrumentedTest {
    @Test
    fun configurationRoundTripsWithoutPathOrPermissionMetadata() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = File(context.cacheDir, "downloads-source-${UUID.randomUUID()}.preferences_pb")
        val fixture = createDownloadsStoreFixture(file)

        try {
            assertEquals(DownloadsSourceConfiguration(), fixture.store.read())

            val configured = DownloadsSourceConfiguration(configured = true, enabled = true)
            fixture.store.write(configured)

            assertEquals(configured, fixture.store.read())
            val persisted = fixture.dataStore.data.first().asMap()
            assertEquals(2, persisted.size)
            assertTrue(
                persisted.keys.none { key ->
                    listOf("path", "uri", "file", "token", "permission").any { forbidden ->
                        key.name.contains(forbidden, ignoreCase = true)
                    }
                },
            )

            val disabled = configured.copy(enabled = false)
            fixture.store.write(disabled)
            assertEquals(disabled, fixture.store.read())
        } finally {
            fixture.close()
            file.delete()
        }
    }

    @Test
    fun configurationSurvivesStoreRecreation() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = File(
            context.cacheDir,
            "downloads-source-restart-${UUID.randomUUID()}.preferences_pb",
        )
        val configured = DownloadsSourceConfiguration(configured = true, enabled = false)

        val first = createDownloadsStoreFixture(file)
        first.store.write(configured)
        first.close()

        val second = createDownloadsStoreFixture(file)
        try {
            assertEquals(configured, second.store.read())
        } finally {
            second.close()
            file.delete()
        }
    }

    @Test
    fun corruptPreferenceFileRecoversToUnconfigured() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = File(
            context.cacheDir,
            "downloads-source-corrupt-${UUID.randomUUID()}.preferences_pb",
        )
        file.writeBytes(byteArrayOf(0x0A, 0x05, 0x01))
        val fixture = createDownloadsStoreFixture(file, recoverCorruption = true)

        try {
            assertEquals(DownloadsSourceConfiguration(), fixture.store.read())
            assertTrue(fixture.dataStore.data.first().asMap().isEmpty())
        } finally {
            fixture.close()
            file.delete()
        }
    }

    @Test
    fun impossibleEnabledOnlyStateRepairsToUnconfigured() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = File(
            context.cacheDir,
            "downloads-source-partial-${UUID.randomUUID()}.preferences_pb",
        )
        val fixture = createDownloadsStoreFixture(file)
        fixture.dataStore.edit { preferences ->
            preferences[booleanPreferencesKey("downloads_enabled")] = true
        }

        try {
            assertEquals(DownloadsSourceConfiguration(), fixture.store.read())
            assertTrue(fixture.dataStore.data.first().asMap().isEmpty())
        } finally {
            fixture.close()
            file.delete()
        }
    }
}

private class DownloadsStoreFixture(
    val scope: CoroutineScope,
    val dataStore: DataStore<Preferences>,
) {
    val store = DataStoreDownloadsSourceStore(dataStore)

    suspend fun close() {
        scope.coroutineContext[Job]?.cancelAndJoin()
    }
}

private fun createDownloadsStoreFixture(
    file: File,
    recoverCorruption: Boolean = false,
): DownloadsStoreFixture {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val dataStore = PreferenceDataStoreFactory.create(
        corruptionHandler = if (recoverCorruption) {
            downloadsSourceCorruptionHandler()
        } else {
            null
        },
        scope = scope,
    ) { file }
    return DownloadsStoreFixture(scope = scope, dataStore = dataStore)
}
