package com.aryasubramani.vijibackup

import android.content.pm.ApplicationInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser

@RunWith(AndroidJUnit4::class)
class AppContextInstrumentedTest {
    @Test
    fun appContextUsesVijiBackupIdentity() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val expectedPackage = when (BuildConfig.FLAVOR) {
            "internal" -> "com.aryasubramani.vijibackup.internal"
            "public" -> "com.aryasubramani.vijibackup"
            else -> error("Unexpected distribution flavor")
        }

        assertEquals(expectedPackage, appContext.packageName)
        assertEquals(expectedPackage, BuildConfig.APPLICATION_ID)
        assertEquals("Viji Backup", appContext.getString(R.string.app_name))
        assertEquals(BuildConfig.FLAVOR, appContext.getString(R.string.app_channel))
    }

    @Test
    fun appDataAutoBackupIsDisabledUntilSecretRulesAreDesigned() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext

        assertFalse((appContext.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP) != 0)
    }

    @Test
    fun folderAccessDatabaseIsExcludedFromEveryBackupTransport() {
        assertEquals(1, databaseExclusionCount(R.xml.backup_rules))
        assertEquals(2, databaseExclusionCount(R.xml.data_extraction_rules))
    }

    private fun databaseExclusionCount(resourceId: Int): Int {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        return appContext.resources.getXml(resourceId).use { parser ->
            var count = 0
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (
                    event == XmlPullParser.START_TAG &&
                    parser.name == "exclude" &&
                    parser.getAttributeValue(null, "domain") == "database" &&
                    parser.getAttributeValue(null, "path") == "."
                ) {
                    count += 1
                }
                event = parser.next()
            }
            count
        }
    }
}
