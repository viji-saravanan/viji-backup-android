package com.aryasubramani.vijibackup.folderaccess.saf

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.ProviderInfo
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import com.aryasubramani.vijibackup.folderaccess.domain.FolderScanEvent
import com.aryasubramani.vijibackup.folderaccess.domain.FolderScanIssue
import com.aryasubramani.vijibackup.folderaccess.domain.FolderScanProgress
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class ContentResolverLocalFolderScannerInstrumentedTest {
    private val targetContext = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var provider: ControllableDocumentsProvider
    private lateinit var treeUri: Uri

    @Before
    fun setUp() {
        val authority = targetContext.packageName + ".scanner.documents"
        treeUri = Uri.parse("content://$authority/tree/${ControllableDocumentsProvider.ROOT_DOCUMENT_ID}")
        provider = ControllableDocumentsProvider().also { documentsProvider ->
            documentsProvider.attachInfo(
                targetContext,
                ProviderInfo().apply {
                    this.authority = authority
                    exported = true
                    grantUriPermissions = true
                    readPermission = Manifest.permission.MANAGE_DOCUMENTS
                    writePermission = Manifest.permission.MANAGE_DOCUMENTS
                },
            )
        }
    }

    @Test
    fun nestedTreeUsesReadOnlyProjectionClosesCursorsAndNeverMutates() = runTest {
        provider.childRowsByParent[ControllableDocumentsProvider.ROOT_DOCUMENT_ID] = listOf(
            row("directory-a", "Folder A", DocumentsContract.Document.MIME_TYPE_DIR),
            row("file-a", "private-file-a.txt", "text/plain", size = 8),
            row("file-b", "private-file-b.txt", "text/plain", size = null),
        )
        provider.childRowsByParent["directory-a"] = listOf(
            row("file-c", "private-file-c.txt", "text/plain", size = 5),
        )

        val events = scanner(provider).scan(treeUri.toString()).toList()
        val terminal = events.last() as FolderScanEvent.Complete

        assertEquals(
            FolderScanProgress(
                foldersVisited = 2,
                filesDiscovered = 3,
                knownBytes = 13,
                filesWithUnknownSize = 1,
            ),
            terminal.summary.progress,
        )
        assertEquals(
            listOf(ControllableDocumentsProvider.ROOT_DOCUMENT_ID, "directory-a"),
            provider.childParentDocumentIds,
        )
        assertTrue(
            provider.childProjections.all {
                it == ControllableDocumentsProvider.DOCUMENT_PROJECTION.toList()
            },
        )
        assertTrue(provider.childCursors.all { it.isClosed })
        assertEquals(0, provider.queryCount)
        assertEquals(0, provider.rootsQueryCount)
        assertEquals(0, provider.openDocumentCallCount)
        assertEquals(0, provider.mutationCallCount)
        assertTrue(events.none { it.toString().contains("private-file") })
    }

    @Test
    fun malformedRowsAndColumnsArePartialWhileUsableSiblingsContinue() = runTest {
        provider.childRowsByParent[ControllableDocumentsProvider.ROOT_DOCUMENT_ID] = listOf(
            row("directory-a", "Folder A", DocumentsContract.Document.MIME_TYPE_DIR),
            row(null, "private-malformed.txt", "text/plain", size = 10),
        )
        provider.childRowsByParent["directory-a"] = listOf(
            row("file-a", "private-file.txt", "text/plain", size = 4),
        )
        provider.childReturnedColumnsByParent["directory-a"] =
            ControllableDocumentsProvider.DOCUMENT_PROJECTION.filterNot {
                it == DocumentsContract.Document.COLUMN_FLAGS
            }.toTypedArray()

        val terminal = scanner(provider).scan(treeUri.toString()).toList().last()
            as FolderScanEvent.Partial

        assertEquals(2, terminal.summary.progress.foldersVisited)
        assertEquals(2, terminal.summary.progress.unreadableEntries)
        assertEquals(setOf(FolderScanIssue.MalformedEntry), terminal.summary.issues)
        assertEquals(
            listOf(ControllableDocumentsProvider.ROOT_DOCUMENT_ID, "directory-a"),
            provider.childParentDocumentIds,
        )
        assertTrue(provider.childCursors.all { it.isClosed })
    }

    @Test
    fun nullCursorFailsButLoadingAndErrorExtrasProducePartialResults() = runTest {
        provider.childNullCursorParents += ControllableDocumentsProvider.ROOT_DOCUMENT_ID

        val failed = scanner(provider).scan(treeUri.toString()).toList().last()
            as FolderScanEvent.Failed

        assertEquals(setOf(FolderScanIssue.NullCursor), failed.summary.issues)

        provider.childNullCursorParents.clear()
        provider.childExtrasByParent[ControllableDocumentsProvider.ROOT_DOCUMENT_ID] = Bundle().apply {
            putBoolean(DocumentsContract.EXTRA_LOADING, true)
            putString(DocumentsContract.EXTRA_ERROR, "private provider error")
            putString(DocumentsContract.EXTRA_INFO, "informational only")
        }

        val partial = scanner(provider).scan(treeUri.toString()).toList().last()
            as FolderScanEvent.Partial

        assertEquals(
            setOf(FolderScanIssue.ProviderLoading, FolderScanIssue.ProviderError),
            partial.summary.issues,
        )
        assertFalse(partial.toString().contains("private provider error"))
    }

    @Test
    fun providerExceptionIsSanitizedAndClosesEveryPreviouslyReturnedCursor() = runTest {
        provider.childRowsByParent[ControllableDocumentsProvider.ROOT_DOCUMENT_ID] = listOf(
            row("directory-a", "Folder A", DocumentsContract.Document.MIME_TYPE_DIR),
        )
        provider.childFailuresByParent["directory-a"] =
            IllegalStateException("private provider and document detail")

        val terminal = scanner(provider).scan(treeUri.toString()).toList().last()
            as FolderScanEvent.Partial

        assertEquals(setOf(FolderScanIssue.QueryFailure), terminal.summary.issues)
        assertEquals(1, terminal.summary.progress.foldersVisited)
        assertTrue(provider.childCursors.all { it.isClosed })
        assertFalse(terminal.toString().contains("private provider"))
    }

    @Test
    fun repeatedDocumentIdsStopCyclesWithoutOpeningContent() = runTest {
        provider.childRowsByParent[ControllableDocumentsProvider.ROOT_DOCUMENT_ID] = listOf(
            row("directory-a", "Folder A", DocumentsContract.Document.MIME_TYPE_DIR),
            row("file-a", "private-file.txt", "text/plain", size = 3),
            row("file-a", "private-file-copy.txt", "text/plain", size = 3),
        )
        provider.childRowsByParent["directory-a"] = listOf(
            row(
                ControllableDocumentsProvider.ROOT_DOCUMENT_ID,
                "Root again",
                DocumentsContract.Document.MIME_TYPE_DIR,
            ),
        )

        val terminal = scanner(provider).scan(treeUri.toString()).toList().last()
            as FolderScanEvent.Partial

        assertEquals(setOf(FolderScanIssue.RepeatedDocument), terminal.summary.issues)
        assertEquals(1, terminal.summary.progress.filesDiscovered)
        assertEquals(2, terminal.summary.progress.foldersVisited)
        assertEquals(0, provider.openDocumentCallCount)
        assertEquals(0, provider.mutationCallCount)
    }

    @Test
    fun cancellationCancelsActiveSignalEmitsNoTerminalAndRetryGetsFreshSignal() = runBlocking {
        val cancellationProvider = CancellationAwareRootProvider().apply {
            blockQueries = true
        }
        val scanner = scanner(cancellationProvider)
        val events = mutableListOf<FolderScanEvent>()
        val first = launch(Dispatchers.Default) {
            scanner.scan(treeUri.toString()).toList(events)
        }

        try {
            assertTrue(cancellationProvider.queryStarted.await(5, TimeUnit.SECONDS))
            first.cancel()
            withTimeout(5_000) { first.cancelAndJoin() }
            assertTrue(cancellationProvider.cancellationSignals.single()?.isCanceled == true)
            assertTrue(events.none { it !is FolderScanEvent.Progress })

            cancellationProvider.blockQueries = false
            val retry = scanner(cancellationProvider).scan(treeUri.toString()).toList()

            assertTrue(retry.last() is FolderScanEvent.Partial)
            assertEquals(2, cancellationProvider.cancellationSignals.size)
            assertNotSame(
                cancellationProvider.cancellationSignals[0],
                cancellationProvider.cancellationSignals[1],
            )
            assertFalse(cancellationProvider.cancellationSignals[1]?.isCanceled == true)
        } finally {
            cancellationProvider.blockQueries = false
            withTimeout(5_000) { first.cancelAndJoin() }
        }
    }

    @Test
    fun invalidTreeFailsBeforeResolverQuery() = runTest {
        val events = scanner(provider).scan("not-a-tree-uri").toList()

        assertTrue(events.single() is FolderScanEvent.Failed)
        assertEquals(0, provider.childQueryCount)
        assertEquals(0, provider.openDocumentCallCount)
        assertEquals(0, provider.mutationCallCount)
    }

    private fun scanner(contentProvider: android.content.ContentProvider): IterativeLocalFolderScanner {
        val times = ArrayDeque(listOf(100L, 125L))
        return IterativeLocalFolderScanner(
            documentSource = ContentResolverLocalFolderDocumentSource(
                contentResolver = ContentResolver.wrap(contentProvider),
                ioDispatcher = Dispatchers.IO,
            ),
            monotonicClock = { times.removeFirst() },
        )
    }

    private fun row(
        documentId: Any?,
        displayName: Any?,
        mimeType: Any?,
        size: Any? = null,
    ) = TestDocumentRow(
        documentId = documentId,
        displayName = displayName,
        mimeType = mimeType,
        flags = 0,
        size = size,
        lastModified = null,
    )
}
