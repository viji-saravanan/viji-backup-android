package com.aryasubramani.vijibackup

import android.content.pm.ApplicationInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppContextInstrumentedTest {
    @Test
    fun appContextUsesVijiBackupIdentity() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext

        assertTrue(appContext.packageName.startsWith("com.aryasubramani.vijibackup"))
        assertEquals("Viji Backup", appContext.getString(R.string.app_name))
        assertTrue(setOf("internal", "public").contains(appContext.getString(R.string.app_channel)))
    }

    @Test
    fun appDataAutoBackupIsDisabledUntilSecretRulesAreDesigned() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext

        assertFalse((appContext.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP) != 0)
    }
}
