package com.aryasubramani.vijibackup.downloadsaccess.platform

import android.content.Intent
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidDownloadsAccessSettingsIntentFactoryInstrumentedTest {
    @Test
    fun androidElevenUsesResolvableAppSpecificAllFilesSettings() {
        val factory = AndroidDownloadsAccessSettingsIntentFactory(
            packageName = "com.example.backup",
            sdkInt = 30,
            canResolve = { intent ->
                intent.action == Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION
            },
        )

        val intent = factory.create()

        assertEquals(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, intent?.action)
        assertEquals("package:com.example.backup", intent?.dataString)
    }

    @Test
    fun unresolvedAppSpecificSettingsFallsBackToResolvableGlobalSettings() {
        val factory = AndroidDownloadsAccessSettingsIntentFactory(
            packageName = "com.example.backup",
            sdkInt = 36,
            canResolve = { intent ->
                intent.action == Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION
            },
        )

        val intent = factory.create()

        assertEquals(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION, intent?.action)
        assertNull(intent?.data)
    }

    @Test
    fun missingSettingsHandlerReturnsNullInsteadOfLaunchingAnInvalidIntent() {
        val factory = AndroidDownloadsAccessSettingsIntentFactory(
            packageName = "com.example.backup",
            sdkInt = 30,
            canResolve = { false },
        )

        assertNull(factory.create())
    }

    @Test
    fun androidTenAndEarlierDoesNotOfferAllFilesSettings() {
        var resolveCalls = 0
        val factory = AndroidDownloadsAccessSettingsIntentFactory(
            packageName = "com.example.backup",
            sdkInt = 29,
            canResolve = { _: Intent ->
                resolveCalls += 1
                true
            },
        )

        assertNull(factory.create())
        assertEquals(0, resolveCalls)
    }
}
