package com.aryasubramani.vijibackup.downloadsaccess.platform

import com.aryasubramani.vijibackup.downloadsaccess.domain.DownloadsScanEvent
import com.aryasubramani.vijibackup.downloadsaccess.domain.DownloadsScanIssue
import com.aryasubramani.vijibackup.downloadsaccess.domain.DownloadsScanProgress
import com.aryasubramani.vijibackup.downloadsaccess.domain.DownloadsScanSummary
import java.util.ArrayDeque
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IterativeDownloadsScannerTest {
    @Test
    fun unavailableRootFailsWithoutReadingAnyDirectory() = runTest {
        val source = RecordingDownloadsTreeSource(
            rootResult = DownloadsRootResult.Unavailable,
        )

        val events = scanner(source).scan().toList()

        assertEquals(
            DownloadsScanEvent.Failed(
                summary(issues = setOf(DownloadsScanIssue.RootUnavailable)),
            ),
            events.single(),
        )
        assertTrue(source.readParents.isEmpty())
    }

    @Test
    fun permissionLossFailsClosedBeforeReadingAnyDirectory() = runTest {
        val source = RecordingDownloadsTreeSource(
            rootResult = DownloadsRootResult.PermissionMissing,
        )

        val events = scanner(source).scan().toList()

        assertEquals(
            DownloadsScanEvent.Failed(
                summary(issues = setOf(DownloadsScanIssue.PermissionLost)),
            ),
            events.single(),
        )
    }

    @Test
    fun nestedAndHiddenFilesProduceAggregateProgressWithoutNames() = runTest {
        val source = RecordingDownloadsTreeSource { parent, onEntry ->
            when (parent) {
                ROOT -> {
                    onEntry(directory("hidden-directory"))
                    onEntry(file("visible-file", 11))
                }
                "hidden-directory" -> {
                    onEntry(file("hidden-file", null))
                    onEntry(directory("nested-directory"))
                }
                "nested-directory" -> Unit
            }
            openedDirectory()
        }

        val events = scanner(source).scan().toList()

        assertEquals(
            DownloadsScanEvent.Complete(
                summary(
                    progress = DownloadsScanProgress(
                        foldersVisited = 3,
                        filesDiscovered = 2,
                        knownBytes = 11,
                        filesWithUnknownSize = 1,
                    ),
                ),
            ),
            events.last(),
        )
        assertTrue(events.none { it.toString().contains("hidden-file") })
        assertMonotonic(events)
    }

    @Test
    fun deepTreesAreIterativeAndWideTreesCountEveryUniqueFile() = runTest {
        val depth = 10_000
        val width = 2_000
        val source = RecordingDownloadsTreeSource { parent, onEntry ->
            val index = if (parent == ROOT) 0 else parent.removePrefix("directory-").toInt() + 1
            if (index < depth) onEntry(directory("directory-$index"))
            if (parent == ROOT) {
                repeat(width) { fileIndex -> onEntry(file("file-$fileIndex", 1)) }
            }
            openedDirectory()
        }

        val terminal = scanner(source).scan().toList().last()

        assertEquals(
            DownloadsScanEvent.Complete(
                summary(
                    progress = DownloadsScanProgress(
                        foldersVisited = depth.toLong() + 1,
                        filesDiscovered = width.toLong(),
                        knownBytes = width.toLong(),
                    ),
                ),
            ),
            terminal,
        )
    }

    @Test
    fun repeatedEntriesAndRejectedFilesystemEntriesRemainPartialAndContinueSiblings() = runTest {
        val source = RecordingDownloadsTreeSource { parent, onEntry ->
            when (parent) {
                ROOT -> {
                    onEntry(directory("directory-a"))
                    onEntry(directory("directory-a"))
                    onEntry(file("file-a", 7))
                    onEntry(file("file-a", 7))
                    DownloadsDirectoryReadResult(
                        directoryRead = true,
                        unreadableEntries = 2,
                        issues = setOf(
                            DownloadsScanIssue.SymbolicLinkSkipped,
                            DownloadsScanIssue.RootEscapeRejected,
                        ),
                    )
                }
                else -> openedDirectory()
            }
        }

        val terminal = scanner(source).scan().toList().last()

        assertEquals(
            DownloadsScanEvent.Partial(
                summary(
                    progress = DownloadsScanProgress(
                        foldersVisited = 2,
                        filesDiscovered = 1,
                        knownBytes = 7,
                        unreadableEntries = 2,
                    ),
                    issues = setOf(
                        DownloadsScanIssue.RepeatedEntry,
                        DownloadsScanIssue.SymbolicLinkSkipped,
                        DownloadsScanIssue.RootEscapeRejected,
                    ),
                ),
            ),
            terminal,
        )
        assertEquals(listOf(ROOT, "directory-a"), source.readParents)
    }

    @Test
    fun unreadableChildDirectoryDoesNotInterruptReadableSibling() = runTest {
        val source = RecordingDownloadsTreeSource { parent, onEntry ->
            when (parent) {
                ROOT -> {
                    onEntry(directory("unreadable"))
                    onEntry(directory("readable"))
                    openedDirectory()
                }
                "unreadable" -> DownloadsDirectoryReadResult(
                    directoryRead = false,
                    unreadableEntries = 1,
                    issues = setOf(DownloadsScanIssue.DirectoryUnreadable),
                )
                else -> {
                    onEntry(file("surviving-file", 9))
                    openedDirectory()
                }
            }
        }

        val terminal = scanner(source).scan().toList().last() as DownloadsScanEvent.Partial

        assertEquals(2, terminal.summary.progress.foldersVisited)
        assertEquals(1, terminal.summary.progress.filesDiscovered)
        assertEquals(1, terminal.summary.progress.unreadableEntries)
        assertEquals(
            setOf(DownloadsScanIssue.DirectoryUnreadable),
            terminal.summary.issues,
        )
        assertEquals(listOf(ROOT, "unreadable", "readable"), source.readParents)
    }

    @Test
    fun aggregateArithmeticSaturatesInsteadOfWrapping() = runTest {
        val source = RecordingDownloadsTreeSource { _, onEntry ->
            onEntry(file("max", Long.MAX_VALUE))
            onEntry(file("overflow", 10))
            DownloadsDirectoryReadResult(
                directoryRead = true,
                unreadableEntries = Long.MAX_VALUE,
                issues = setOf(DownloadsScanIssue.EntryUnreadable),
            )
        }

        val terminal = scanner(source).scan().toList().last() as DownloadsScanEvent.Partial

        assertEquals(Long.MAX_VALUE, terminal.summary.progress.knownBytes)
        assertEquals(Long.MAX_VALUE, terminal.summary.progress.unreadableEntries)
        assertEquals(2, terminal.summary.progress.filesDiscovered)
    }

    @Test
    fun cancellationAtRootAndDirectoryBoundariesNeverEmitsATerminalEvent() = runTest {
        val rootCancelled = object : DownloadsTreeSource {
            override fun root(): DownloadsRootResult {
                throw CancellationException("cancelled root")
            }

            override suspend fun readChildren(
                parentId: String,
                onEntry: suspend (DownloadsTreeEntry) -> Unit,
            ): DownloadsDirectoryReadResult = error("must not read")
        }
        val rootEvents = mutableListOf<DownloadsScanEvent>()
        var rootCancellation: CancellationException? = null
        try {
            scanner(rootCancelled).scan().toList(rootEvents)
        } catch (cancelled: CancellationException) {
            rootCancellation = cancelled
        }
        assertTrue(rootCancellation != null)
        assertTrue(rootEvents.none { it.isTerminal() })

        val readStarted = CompletableDeferred<Unit>()
        val directoryCancelled = RecordingDownloadsTreeSource { _, _ ->
            readStarted.complete(Unit)
            awaitCancellation()
        }
        val directoryEvents = mutableListOf<DownloadsScanEvent>()
        val collection = launch {
            scanner(directoryCancelled).scan().toList(directoryEvents)
        }
        readStarted.await()
        collection.cancelAndJoin()
        assertTrue(directoryEvents.none { it.isTerminal() })
    }

    @Test
    fun stoppingCollectionAfterProgressDoesNotReadQueuedDirectory() = runTest {
        val source = RecordingDownloadsTreeSource { _, onEntry ->
            onEntry(file("file-a", 1))
            onEntry(directory("directory-a"))
            openedDirectory()
        }

        val events = scanner(source).scan().take(1).toList()

        assertEquals(1, events.size)
        assertTrue(events.single() is DownloadsScanEvent.Progress)
        assertEquals(listOf(ROOT), source.readParents)
    }

    private fun scanner(source: DownloadsTreeSource): IterativeDownloadsScanner {
        val times = ArrayDeque(listOf(100L, 125L))
        return IterativeDownloadsScanner(source, monotonicClock = { times.removeFirst() })
    }

    private fun assertMonotonic(events: List<DownloadsScanEvent>) {
        val progress = events.mapNotNull { (it as? DownloadsScanEvent.Progress)?.value }
        progress.zipWithNext().forEach { (previous, next) ->
            assertTrue(previous.foldersVisited <= next.foldersVisited)
            assertTrue(previous.filesDiscovered <= next.filesDiscovered)
            assertTrue(previous.knownBytes <= next.knownBytes)
            assertTrue(previous.filesWithUnknownSize <= next.filesWithUnknownSize)
            assertTrue(previous.unreadableEntries <= next.unreadableEntries)
        }
    }

    private fun DownloadsScanEvent.isTerminal() = this !is DownloadsScanEvent.Progress

    private companion object {
        const val ROOT = "root"
    }
}

private class RecordingDownloadsTreeSource(
    private val rootResult: DownloadsRootResult = DownloadsRootResult.Found("root"),
    private val read: suspend (
        String,
        suspend (DownloadsTreeEntry) -> Unit,
    ) -> DownloadsDirectoryReadResult = { _, _ -> openedDirectory() },
) : DownloadsTreeSource {
    val readParents = mutableListOf<String>()

    override fun root(): DownloadsRootResult = rootResult

    override suspend fun readChildren(
        parentId: String,
        onEntry: suspend (DownloadsTreeEntry) -> Unit,
    ): DownloadsDirectoryReadResult {
        readParents += parentId
        return read(parentId, onEntry)
    }
}

private fun directory(id: String) = DownloadsTreeEntry(id, isDirectory = true, size = null)

private fun file(id: String, size: Long?) =
    DownloadsTreeEntry(id, isDirectory = false, size = size)

private fun openedDirectory() = DownloadsDirectoryReadResult(directoryRead = true)

private fun summary(
    progress: DownloadsScanProgress = DownloadsScanProgress(),
    issues: Set<DownloadsScanIssue> = emptySet(),
) = DownloadsScanSummary(progress, elapsedTimeMillis = 25, issues = issues)
