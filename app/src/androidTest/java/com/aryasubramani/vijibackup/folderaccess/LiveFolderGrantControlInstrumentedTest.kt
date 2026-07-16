package com.aryasubramani.vijibackup.folderaccess

import android.content.Context
import android.content.Intent
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aryasubramani.vijibackup.folderaccess.data.db.VijiBackupDatabase
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiveFolderGrantControlInstrumentedTest {
    @Test
    fun releasesExactlyOneExplicitlySelectedLiveGrant() {
        val arguments = InstrumentationRegistry.getArguments()
        if (!arguments.getString(ARG_ENABLED).toBoolean()) return

        val expectedDisplayNameHash = arguments.requiredString(ARG_DISPLAY_NAME_SHA256)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.databaseBuilder(
            context,
            VijiBackupDatabase::class.java,
            VijiBackupDatabase.DATABASE_NAME,
        ).build()

        try {
            val mapping = runBlocking {
                database.folderAccessDao().allMappings().single { candidate ->
                    candidate.sourceDisplayName?.sha256() == expectedDisplayNameHash
                }
            }
            val resolver = context.contentResolver
            val permission = resolver.persistedUriPermissions.single { persisted ->
                persisted.uri.toString() == mapping.treeUri
            }
            assertTrue(permission.isReadPermission)

            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                (if (permission.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
            resolver.releasePersistableUriPermission(permission.uri, flags)

            assertFalse(
                resolver.persistedUriPermissions.any { persisted ->
                    persisted.uri.toString() == mapping.treeUri && persisted.isReadPermission
                },
            )
        } finally {
            database.close()
        }
    }

    private fun android.os.Bundle.requiredString(key: String): String =
        requireNotNull(getString(key)).also { value ->
            require(value.matches(Regex("[a-f0-9]{64}")))
        }

    private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private companion object {
        const val ARG_ENABLED = "live_grant_control_enabled"
        const val ARG_DISPLAY_NAME_SHA256 = "display_name_sha256"
    }
}
