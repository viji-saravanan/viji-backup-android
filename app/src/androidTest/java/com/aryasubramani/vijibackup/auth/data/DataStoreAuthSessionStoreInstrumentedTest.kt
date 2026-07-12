package com.aryasubramani.vijibackup.auth.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aryasubramani.vijibackup.auth.domain.GoogleAccount
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DataStoreAuthSessionStoreInstrumentedTest {
    @Test
    fun approvedMetadataRoundTripsWithoutTokenMaterialAndClears() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = File(context.cacheDir, "auth-session-${UUID.randomUUID()}.preferences_pb")
        val fixture = createStoreFixture(file)
        val dataStore = fixture.dataStore
        val store = fixture.store
        val account = requireNotNull(
            GoogleAccount.create(
                subject = "test-approved-google-subject",
                email = "primary.user@example.test",
                displayName = "Primary User",
            ),
        )

        try {
            assertNull(store.read())

            store.save(account)

            assertEquals(account, store.read())
            val persistedPreferences = dataStore.data.first().asMap()
            assertEquals(3, persistedPreferences.size)
            assertTrue(
                persistedPreferences.keys.none { key ->
                    key.name.contains("token", ignoreCase = true)
                },
            )

            val accountWithoutDisplayName = requireNotNull(
                GoogleAccount.create(
                    subject = account.subject,
                    email = account.email,
                    displayName = null,
                ),
            )
            store.save(accountWithoutDisplayName)

            assertEquals(accountWithoutDisplayName, store.read())
            assertEquals(2, dataStore.data.first().asMap().size)

            store.clear()

            assertNull(store.read())
            assertTrue(dataStore.data.first().asMap().isEmpty())

            dataStore.edit { preferences ->
                preferences[stringPreferencesKey("google_account_email")] = "primary.user@example.test"
            }

            assertNull(store.read())
            assertTrue(dataStore.data.first().asMap().isEmpty())

            store.beginProviderCleanup()
            assertTrue(store.isProviderCleanupPending())

            store.save(account)

            assertEquals(account, store.read())
            assertFalse(store.isProviderCleanupPending())
        } finally {
            fixture.close()
            file.delete()
        }
    }

    @Test
    fun approvedMetadataAndClearSurviveStoreRecreation() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = File(context.cacheDir, "auth-session-restart-${UUID.randomUUID()}.preferences_pb")
        val account = requireNotNull(
            GoogleAccount.create(
                subject = "test-restart-google-subject",
                email = "restart.user@example.test",
                displayName = "Restart User",
            ),
        )

        val first = createStoreFixture(file)
        first.store.save(account)
        first.close()

        val second = createStoreFixture(file)
        assertEquals(account, second.store.read())
        second.store.clear()
        second.close()

        val third = createStoreFixture(file)
        try {
            assertNull(third.store.read())
        } finally {
            third.close()
            file.delete()
        }
    }

    @Test
    fun corruptPreferenceFileRecoversToSignedOut() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = File(context.cacheDir, "auth-session-corrupt-${UUID.randomUUID()}.preferences_pb")
        file.writeBytes(byteArrayOf(0x0A, 0x05, 0x01))
        val fixture = createStoreFixture(file, recoverCorruption = true)

        try {
            assertNull(fixture.store.read())
            assertTrue(fixture.dataStore.data.first().asMap().isEmpty())
        } finally {
            fixture.close()
            file.delete()
        }
    }

    @Test
    fun providerCleanupMarkerSurvivesRestartUntilExplicitCompletion() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = File(context.cacheDir, "auth-cleanup-${UUID.randomUUID()}.preferences_pb")

        val first = createStoreFixture(file)
        first.store.save(
            requireNotNull(
                GoogleAccount.create(
                    subject = "cleanup-subject",
                    email = "cleanup.user@example.test",
                    displayName = null,
                ),
            ),
        )
        first.store.beginProviderCleanup()
        assertNull(first.store.read())
        assertTrue(first.store.isProviderCleanupPending())
        first.close()

        val second = createStoreFixture(file)
        try {
            assertNull(second.store.read())
            assertTrue(second.store.isProviderCleanupPending())

            second.store.completeProviderCleanup()

            assertTrue(second.dataStore.data.first().asMap().isEmpty())
        } finally {
            second.close()
            file.delete()
        }
    }
}

private class StoreFixture(
    val scope: CoroutineScope,
    val dataStore: DataStore<Preferences>,
) {
    val store = DataStoreAuthSessionStore(dataStore)

    suspend fun close() {
        scope.coroutineContext[Job]?.cancelAndJoin()
    }
}

private fun createStoreFixture(
    file: File,
    recoverCorruption: Boolean = false,
): StoreFixture {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val dataStore = PreferenceDataStoreFactory.create(
        corruptionHandler = if (recoverCorruption) authSessionCorruptionHandler() else null,
        scope = scope,
    ) { file }
    return StoreFixture(scope = scope, dataStore = dataStore)
}
