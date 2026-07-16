package com.aryasubramani.vijibackup.downloadsaccess

import android.os.Build
import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aryasubramani.vijibackup.app.VijiBackupApplication
import com.aryasubramani.vijibackup.downloadsaccess.domain.DownloadsAccessHealth
import com.aryasubramani.vijibackup.downloadsaccess.domain.DownloadsAccessResult
import com.aryasubramani.vijibackup.downloadsaccess.domain.DownloadsScanEvent
import com.aryasubramani.vijibackup.downloadsaccess.domain.DownloadsScanIssue
import java.io.File
import java.security.MessageDigest
import java.util.ArrayDeque
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiveDownloadsScannerInstrumentedTest {
    @Test
    fun realPermissionRevocationFailsClosedBeforeAnyDownloadsRead() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        assertFalse(Environment.isExternalStorageManager())
        val application = ApplicationProvider.getApplicationContext<VijiBackupApplication>()
        val container = application.appContainer

        val access = container.downloadsAccessManager.refresh() as DownloadsAccessResult.Success
        assertTrue(access.snapshot.health != DownloadsAccessHealth.Ready)
        val events = container.downloadsScanner.scan().toList()

        val failure = events.single() as DownloadsScanEvent.Failed
        assertEquals(setOf(DownloadsScanIssue.PermissionLost), failure.summary.issues)
        assertEquals(0, failure.summary.progress.foldersVisited)
        assertEquals(0, failure.summary.progress.filesDiscovered)
    }

    @Test
    fun realPrimaryDownloadsScanIsCancellableRetryableAndSourceImmutable() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        assertTrue(Environment.isExternalStorageManager())
        val application = ApplicationProvider.getApplicationContext<VijiBackupApplication>()
        val container = application.appContainer
        val configured = container.downloadsAccessManager.configureFromCurrentPermission()
            as DownloadsAccessResult.Success
        assertEquals(DownloadsAccessHealth.Ready, configured.snapshot.health)
        val root = primaryDownloadsRoot()
        val before = root.contentSentinel()

        val cancelledCollection = container.downloadsScanner.scan().take(1).toList()
        assertEquals(1, cancelledCollection.size)
        assertFalse(cancelledCollection.single().isTerminal())

        val events = container.downloadsScanner.scan().toList()
        val terminal = events.last()
        assertTrue(
            terminal is DownloadsScanEvent.Complete || terminal is DownloadsScanEvent.Partial,
        )
        assertEquals(1, events.count { it.isTerminal() })

        val after = root.contentSentinel()
        assertEquals(before.entryCount, after.entryCount)
        assertEquals(before.unreadableEntries, after.unreadableEntries)
        assertArrayEquals(before.digest, after.digest)
    }
}

private data class ContentSentinel(
    val entryCount: Long,
    val unreadableEntries: Long,
    val digest: ByteArray,
)

@Suppress("DEPRECATION")
private fun primaryDownloadsRoot(): File =
    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).canonicalFile

private fun File.contentSentinel(): ContentSentinel {
    val root = canonicalFile
    val rootPrefix = root.path + File.separator
    val pending = ArrayDeque<File>()
    val seenDirectories = mutableSetOf(root.path)
    val records = mutableListOf<String>()
    var unreadable = 0L
    pending.addLast(root)

    while (pending.isNotEmpty()) {
        val directory = pending.removeFirst()
        val children = try {
            directory.listFiles()
        } catch (_: Exception) {
            null
        }
        if (children == null) {
            unreadable += 1
            continue
        }
        children.forEach { child ->
            val canonical = try {
                child.canonicalFile
            } catch (_: Exception) {
                unreadable += 1
                return@forEach
            }
            val relative = child.relativeTo(root).path
            val insideRoot = canonical.path == root.path || canonical.path.startsWith(rootPrefix)
            val symbolicLink = File(directory, child.name).absolutePath != canonical.absolutePath
            val kind = when {
                !insideRoot -> "escape"
                symbolicLink -> "link"
                canonical.isDirectory -> "directory"
                canonical.isFile -> "file"
                else -> "other"
            }
            records += listOf(
                relative,
                kind,
                canonical.length().toString(),
                canonical.lastModified().toString(),
            ).joinToString("\u0000")
            if (
                kind == "directory" &&
                seenDirectories.add(canonical.path)
            ) {
                pending.addLast(canonical)
            }
        }
    }

    val digest = MessageDigest.getInstance("SHA-256")
    records.sorted().forEach { record ->
        digest.update(record.toByteArray(Charsets.UTF_8))
        digest.update(0.toByte())
    }
    return ContentSentinel(
        entryCount = records.size.toLong(),
        unreadableEntries = unreadable,
        digest = digest.digest(),
    )
}

private fun DownloadsScanEvent.isTerminal(): Boolean = this !is DownloadsScanEvent.Progress
