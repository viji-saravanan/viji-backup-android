package com.aryasubramani.vijibackup.downloadsaccess.platform

import com.aryasubramani.vijibackup.downloadsaccess.domain.DownloadsScanEvent
import com.aryasubramani.vijibackup.downloadsaccess.domain.DownloadsScanIssue
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.toList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileDownloadsTreeSourceTest {
    @Test
    fun deniedPermissionAndUnavailableRootFailBeforeListing() = runTest {
        val root = Files.createTempDirectory("downloads-root").toFile()
        try {
            assertEquals(
                DownloadsRootResult.PermissionMissing,
                source(root, hasAccess = false).root(),
            )
            assertEquals(
                DownloadsRootResult.Unavailable,
                source(File(root, "missing")).root(),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun realNestedAndHiddenFilesystemContentIsReadWithoutMutation() = runTest {
        val root = Files.createTempDirectory("downloads-root").toFile()
        try {
            val hidden = File(root, ".hidden").apply { mkdir() }
            File(root, "visible.bin").writeBytes(ByteArray(11))
            File(hidden, ".private.bin").writeBytes(ByteArray(7))
            val before = root.contentSentinel()

            val terminal = IterativeDownloadsScanner(source(root))
                .scan()
                .toList()
                .last() as DownloadsScanEvent.Complete

            assertEquals(2, terminal.summary.progress.foldersVisited)
            assertEquals(2, terminal.summary.progress.filesDiscovered)
            assertEquals(18, terminal.summary.progress.knownBytes)
            assertEquals(before, root.contentSentinel())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun symlinksAreNeverFollowedAndOutsideTargetsAreRejected() = runTest {
        val sandbox = Files.createTempDirectory("downloads-sandbox").toFile()
        val root = File(sandbox, "Downloads").apply { mkdir() }
        val inside = File(root, "inside").apply { mkdir() }
        val outside = File(sandbox, "outside").apply { mkdir() }
        try {
            File(inside, "inside.bin").writeBytes(byteArrayOf(1))
            File(outside, "outside.bin").writeBytes(byteArrayOf(2))
            Files.createSymbolicLink(File(root, "inside-link").toPath(), inside.toPath())
            Files.createSymbolicLink(File(root, "outside-link").toPath(), outside.toPath())

            val terminal = IterativeDownloadsScanner(source(root))
                .scan()
                .toList()
                .last() as DownloadsScanEvent.Partial

            assertEquals(1, terminal.summary.progress.filesDiscovered)
            assertEquals(2, terminal.summary.progress.unreadableEntries)
            assertEquals(
                setOf(
                    DownloadsScanIssue.SymbolicLinkSkipped,
                    DownloadsScanIssue.RootEscapeRejected,
                ),
                terminal.summary.issues,
            )
        } finally {
            sandbox.deleteRecursively()
        }
    }

    @Test
    fun permissionRevocationAndOutsideParentAreTypedReadFailures() = runTest {
        val sandbox = Files.createTempDirectory("downloads-sandbox").toFile()
        val root = File(sandbox, "Downloads").apply { mkdir() }
        var hasAccess = true
        val source = FileDownloadsTreeSource(
            rootDirectory = { root },
            hasAccess = { hasAccess },
        )
        try {
            val rootId = (source.root() as DownloadsRootResult.Found).rootId
            hasAccess = false

            val revoked = source.readChildren(rootId) {}
            assertFalse(revoked.directoryRead)
            assertEquals(setOf(DownloadsScanIssue.PermissionLost), revoked.issues)

            hasAccess = true
            val escaped = source.readChildren(sandbox.canonicalPath) {}
            assertFalse(escaped.directoryRead)
            assertEquals(1, escaped.unreadableEntries)
            assertEquals(setOf(DownloadsScanIssue.RootEscapeRejected), escaped.issues)
        } finally {
            sandbox.deleteRecursively()
        }
    }

    @Test
    fun childDisappearingBetweenListingAndInspectionDoesNotStopItsSibling() = runTest {
        val root = Files.createTempDirectory("downloads-root").toFile()
        val disappearing = File(root, "disappearing.bin").apply { writeBytes(byteArrayOf(1)) }
        File(root, "surviving.bin").writeBytes(byteArrayOf(2, 3))
        val source = FileDownloadsTreeSource(
            rootDirectory = { root },
            hasAccess = { true },
            fileSystem = object : DownloadsReadOnlyFileSystem by JavaIoDownloadsReadOnlyFileSystem {
                override fun exists(file: File): Boolean =
                    file.name != disappearing.name &&
                        JavaIoDownloadsReadOnlyFileSystem.exists(file)
            },
        )
        try {
            val terminal = IterativeDownloadsScanner(source)
                .scan()
                .toList()
                .last() as DownloadsScanEvent.Partial

            assertEquals(1, terminal.summary.progress.filesDiscovered)
            assertEquals(2, terminal.summary.progress.knownBytes)
            assertEquals(1, terminal.summary.progress.unreadableEntries)
            assertTrue(DownloadsScanIssue.EntryUnreadable in terminal.summary.issues)
        } finally {
            root.deleteRecursively()
        }
    }
}

private fun source(root: File, hasAccess: Boolean = true) = FileDownloadsTreeSource(
    rootDirectory = { root },
    hasAccess = { hasAccess },
)

private fun File.contentSentinel(): List<String> = walkTopDown()
    .map { entry ->
        val kind = if (entry.isDirectory) "d" else "f"
        "$kind:${entry.relativeTo(this).path}:${entry.length()}:${entry.lastModified()}"
    }
    .sorted()
    .toList()
