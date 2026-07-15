package com.aryasubramani.vijibackup.folderaccess.saf

import com.aryasubramani.vijibackup.folderaccess.domain.FolderScanEvent
import com.aryasubramani.vijibackup.folderaccess.domain.FolderScanIssue
import com.aryasubramani.vijibackup.folderaccess.domain.FolderScanProgress
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

class IterativeLocalFolderScannerTest {
    @Test
    fun scanIsColdAndInvalidTreeFailsWithoutQuerying() = runTest {
        val source = RecordingDocumentSource(rootDocumentId = null)
        val scanner = scanner(source)
        val scan = scanner.scan(TREE_URI)

        assertTrue(source.queriedParents.isEmpty())

        val events = scan.toList()

        assertEquals(
            FolderScanEvent.Failed(
                summary(
                    progress = FolderScanProgress(),
                    elapsedTimeMillis = 25,
                    issues = setOf(FolderScanIssue.InvalidTree),
                ),
            ),
            events.single(),
        )
        assertTrue(source.queriedParents.isEmpty())
        assertExactlyOneTerminal(events)
    }

    @Test
    fun cancellationDuringRootLookupPropagatesWithoutTerminalEvent() = runTest {
        val source = object : LocalFolderDocumentSource {
            override fun rootDocumentId(treeUri: String): String? {
                throw CancellationException("cancelled root lookup")
            }

            override suspend fun queryChildren(
                treeUri: String,
                parentDocumentId: String,
                onDocument: suspend (FolderDocumentMetadata) -> Unit,
            ): FolderDocumentQueryResult = error("Query must not start")
        }
        val events = mutableListOf<FolderScanEvent>()
        var observedCancellation: CancellationException? = null

        try {
            scanner(source).scan(TREE_URI).toList(events)
        } catch (cancelled: CancellationException) {
            observedCancellation = cancelled
        }

        assertTrue(observedCancellation != null)
        assertTrue(events.none { it.isTerminal() })
    }

    @Test
    fun emptyTreeCompletesAndCountsTheReturnedRootCursor() = runTest {
        val source = RecordingDocumentSource()

        val events = scanner(source).scan(TREE_URI).toList()

        assertEquals(
            FolderScanEvent.Complete(
                summary(
                    progress = FolderScanProgress(foldersVisited = 1),
                    elapsedTimeMillis = 25,
                ),
            ),
            events.last(),
        )
        assertEquals(listOf(ROOT_ID), source.queriedParents)
        assertMonotonicProgress(events)
        assertExactlyOneTerminal(events)
    }

    @Test
    fun nestedTreeStreamsMonotonicProgressWithoutRetainingNames() = runTest {
        val source = RecordingDocumentSource { parent, onDocument ->
            when (parent) {
                ROOT_ID -> {
                    onDocument(directory("directory-a"))
                    onDocument(file("file-a", size = 11))
                }
                "directory-a" -> {
                    onDocument(file("file-b", size = null))
                    onDocument(directory("directory-b"))
                }
                "directory-b" -> Unit
            }
            returnedCursor()
        }

        val events = scanner(source).scan(TREE_URI).toList()

        assertEquals(
            FolderScanEvent.Complete(
                summary(
                    progress = FolderScanProgress(
                        foldersVisited = 3,
                        filesDiscovered = 2,
                        knownBytes = 11,
                        filesWithUnknownSize = 1,
                    ),
                    elapsedTimeMillis = 25,
                ),
            ),
            events.last(),
        )
        assertEquals(listOf(ROOT_ID, "directory-a", "directory-b"), source.queriedParents)
        assertMonotonicProgress(events)
        assertExactlyOneTerminal(events)
    }

    @Test
    fun deepTreeUsesIterationInsteadOfTheCallStack() = runTest {
        val depth = 10_000
        val source = RecordingDocumentSource { parent, onDocument ->
            val index = if (parent == ROOT_ID) 0 else parent.removePrefix("directory-").toInt() + 1
            if (index < depth) {
                onDocument(directory("directory-$index"))
            }
            returnedCursor()
        }

        val terminal = scanner(source).scan(TREE_URI).toList().last()

        assertEquals(
            FolderScanEvent.Complete(
                summary(
                    progress = FolderScanProgress(foldersVisited = depth.toLong() + 1),
                    elapsedTimeMillis = 25,
                ),
            ),
            terminal,
        )
        assertEquals(depth + 1, source.queriedParents.size)
    }

    @Test
    fun wideTreeCountsEachUniqueFileOnce() = runTest {
        val fileCount = 2_000
        val source = RecordingDocumentSource { _, onDocument ->
            repeat(fileCount) { index -> onDocument(file("file-$index", size = 1)) }
            returnedCursor()
        }

        val terminal = scanner(source).scan(TREE_URI).toList().last()

        assertEquals(
            FolderScanEvent.Complete(
                summary(
                    progress = FolderScanProgress(
                        foldersVisited = 1,
                        filesDiscovered = fileCount.toLong(),
                        knownBytes = fileCount.toLong(),
                    ),
                    elapsedTimeMillis = 25,
                ),
            ),
            terminal,
        )
    }

    @Test
    fun repeatedDocumentsSelfCyclesAndMultiNodeCyclesAreSkipped() = runTest {
        val source = RecordingDocumentSource { parent, onDocument ->
            when (parent) {
                ROOT_ID -> {
                    onDocument(directory(ROOT_ID))
                    onDocument(directory("directory-a"))
                    onDocument(file("file-a", size = 7))
                    onDocument(file("file-a", size = 7))
                }
                "directory-a" -> onDocument(directory("directory-b"))
                "directory-b" -> onDocument(directory("directory-a"))
            }
            returnedCursor()
        }

        val events = scanner(source).scan(TREE_URI).toList()

        assertEquals(
            FolderScanEvent.Partial(
                summary(
                    progress = FolderScanProgress(
                        foldersVisited = 3,
                        filesDiscovered = 1,
                        knownBytes = 7,
                    ),
                    elapsedTimeMillis = 25,
                    issues = setOf(FolderScanIssue.RepeatedDocument),
                ),
            ),
            events.last(),
        )
        assertEquals(listOf(ROOT_ID, "directory-a", "directory-b"), source.queriedParents)
        assertExactlyOneTerminal(events)
    }

    @Test
    fun aggregateArithmeticSaturatesInsteadOfWrapping() = runTest {
        val source = RecordingDocumentSource { parent, onDocument ->
            when (parent) {
                ROOT_ID -> {
                    onDocument(file("file-max", Long.MAX_VALUE))
                    onDocument(file("file-overflow", 10))
                    onDocument(directory("directory-a"))
                    FolderDocumentQueryResult(
                        cursorReturned = true,
                        unreadableEntries = Long.MAX_VALUE,
                        issues = setOf(FolderScanIssue.MalformedEntry),
                    )
                }
                else -> FolderDocumentQueryResult(
                    cursorReturned = true,
                    unreadableEntries = 1,
                    issues = setOf(FolderScanIssue.MalformedEntry),
                )
            }
        }

        val terminal = scanner(source).scan(TREE_URI).toList().last() as FolderScanEvent.Partial

        assertEquals(Long.MAX_VALUE, terminal.summary.progress.knownBytes)
        assertEquals(Long.MAX_VALUE, terminal.summary.progress.unreadableEntries)
        assertEquals(2, terminal.summary.progress.filesDiscovered)
        assertEquals(2, terminal.summary.progress.foldersVisited)
    }

    @Test
    fun nullCursorBeforeAnyUsableDirectoryFails() = runTest {
        val source = RecordingDocumentSource { _, _ ->
            FolderDocumentQueryResult(
                cursorReturned = false,
                issues = setOf(FolderScanIssue.NullCursor),
            )
        }

        val events = scanner(source).scan(TREE_URI).toList()

        assertEquals(
            FolderScanEvent.Failed(
                summary(
                    progress = FolderScanProgress(),
                    elapsedTimeMillis = 25,
                    issues = setOf(FolderScanIssue.NullCursor),
                ),
            ),
            events.last(),
        )
        assertExactlyOneTerminal(events)
    }

    @Test
    fun failureAfterAUsableDirectoryProducesPartialSummary() = runTest {
        val source = RecordingDocumentSource { parent, onDocument ->
            if (parent == ROOT_ID) {
                onDocument(directory("directory-a"))
                returnedCursor()
            } else {
                FolderDocumentQueryResult(
                    cursorReturned = false,
                    issues = setOf(FolderScanIssue.QueryFailure),
                )
            }
        }

        val events = scanner(source).scan(TREE_URI).toList()

        assertEquals(
            FolderScanEvent.Partial(
                summary(
                    progress = FolderScanProgress(foldersVisited = 1),
                    elapsedTimeMillis = 25,
                    issues = setOf(FolderScanIssue.QueryFailure),
                ),
            ),
            events.last(),
        )
        assertExactlyOneTerminal(events)
    }

    @Test
    fun loadingErrorAndMalformedResultsRemainPartialAndContinueSiblings() = runTest {
        val source = RecordingDocumentSource { parent, onDocument ->
            if (parent == ROOT_ID) {
                onDocument(directory("directory-a"))
                onDocument(directory("directory-b"))
                FolderDocumentQueryResult(
                    cursorReturned = true,
                    issues = setOf(FolderScanIssue.ProviderLoading),
                )
            } else if (parent == "directory-a") {
                FolderDocumentQueryResult(
                    cursorReturned = true,
                    unreadableEntries = 2,
                    issues = setOf(FolderScanIssue.MalformedEntry),
                )
            } else {
                FolderDocumentQueryResult(
                    cursorReturned = true,
                    issues = setOf(FolderScanIssue.ProviderError),
                )
            }
        }

        val terminal = scanner(source).scan(TREE_URI).toList().last()

        assertEquals(
            FolderScanEvent.Partial(
                summary(
                    progress = FolderScanProgress(
                        foldersVisited = 3,
                        unreadableEntries = 2,
                    ),
                    elapsedTimeMillis = 25,
                    issues = setOf(
                        FolderScanIssue.ProviderLoading,
                        FolderScanIssue.ProviderError,
                        FolderScanIssue.MalformedEntry,
                    ),
                ),
            ),
            terminal,
        )
        assertEquals(listOf(ROOT_ID, "directory-a", "directory-b"), source.queriedParents)
    }

    @Test
    fun unexpectedSourceExceptionIsSanitizedAsQueryFailure() = runTest {
        val source = RecordingDocumentSource { _, _ ->
            throw IllegalStateException("private provider and document details")
        }

        val terminal = scanner(source).scan(TREE_URI).toList().last()

        assertEquals(
            FolderScanEvent.Failed(
                summary(
                    progress = FolderScanProgress(),
                    elapsedTimeMillis = 25,
                    issues = setOf(FolderScanIssue.QueryFailure),
                ),
            ),
            terminal,
        )
        assertTrue(terminal.toString().contains("private provider").not())
    }

    @Test
    fun cancellationDuringAQueryPropagatesWithoutTerminalEvent() = runTest {
        val queryStarted = CompletableDeferred<Unit>()
        val source = RecordingDocumentSource { _, _ ->
            queryStarted.complete(Unit)
            awaitCancellation()
        }
        val events = mutableListOf<FolderScanEvent>()

        val collection = launch {
            scanner(source).scan(TREE_URI).toList(events)
        }
        queryStarted.await()
        collection.cancelAndJoin()

        assertTrue(collection.isCancelled)
        assertTrue(events.none { it.isTerminal() })
    }

    @Test
    fun stoppingCollectionBetweenRowsDoesNotQueryQueuedDirectoriesOrEmitTerminal() = runTest {
        val source = RecordingDocumentSource { _, onDocument ->
            onDocument(file("file-a", 1))
            onDocument(directory("directory-a"))
            returnedCursor()
        }

        val events = scanner(source).scan(TREE_URI).take(1).toList()

        assertEquals(1, events.size)
        assertTrue(events.single() is FolderScanEvent.Progress)
        assertEquals(listOf(ROOT_ID), source.queriedParents)
        assertTrue(events.none { it.isTerminal() })
    }

    private fun scanner(source: LocalFolderDocumentSource): IterativeLocalFolderScanner {
        val times = ArrayDeque(listOf(100L, 125L))
        return IterativeLocalFolderScanner(
            documentSource = source,
            monotonicClock = { times.removeFirst() },
        )
    }

    private fun assertMonotonicProgress(events: List<FolderScanEvent>) {
        val progress = events.mapNotNull { (it as? FolderScanEvent.Progress)?.value }
        progress.zipWithNext().forEach { (previous, next) ->
            assertTrue(previous.foldersVisited <= next.foldersVisited)
            assertTrue(previous.filesDiscovered <= next.filesDiscovered)
            assertTrue(previous.knownBytes <= next.knownBytes)
            assertTrue(previous.filesWithUnknownSize <= next.filesWithUnknownSize)
            assertTrue(previous.unreadableEntries <= next.unreadableEntries)
        }
    }

    private fun assertExactlyOneTerminal(events: List<FolderScanEvent>) {
        assertEquals(1, events.count { it.isTerminal() })
        assertTrue(events.last().isTerminal())
    }

    private fun FolderScanEvent.isTerminal(): Boolean = this !is FolderScanEvent.Progress

    private companion object {
        const val TREE_URI = "content://provider.test/tree/root"
        const val ROOT_ID = "root"
    }
}

private class RecordingDocumentSource(
    private val rootDocumentId: String? = "root",
    private val query: suspend (
        parentDocumentId: String,
        onDocument: suspend (FolderDocumentMetadata) -> Unit,
    ) -> FolderDocumentQueryResult = { _, _ -> returnedCursor() },
) : LocalFolderDocumentSource {
    val queriedParents = mutableListOf<String>()

    override fun rootDocumentId(treeUri: String): String? = rootDocumentId

    override suspend fun queryChildren(
        treeUri: String,
        parentDocumentId: String,
        onDocument: suspend (FolderDocumentMetadata) -> Unit,
    ): FolderDocumentQueryResult {
        queriedParents += parentDocumentId
        return query(parentDocumentId, onDocument)
    }
}

private fun returnedCursor() = FolderDocumentQueryResult(cursorReturned = true)

private fun directory(documentId: String) = FolderDocumentMetadata(
    documentId = documentId,
    isDirectory = true,
    size = null,
)

private fun file(documentId: String, size: Long?) = FolderDocumentMetadata(
    documentId = documentId,
    isDirectory = false,
    size = size,
)

private fun summary(
    progress: FolderScanProgress,
    elapsedTimeMillis: Long,
    issues: Set<FolderScanIssue> = emptySet(),
) = com.aryasubramani.vijibackup.folderaccess.domain.FolderScanSummary(
    progress = progress,
    elapsedTimeMillis = elapsedTimeMillis,
    issues = issues,
)
