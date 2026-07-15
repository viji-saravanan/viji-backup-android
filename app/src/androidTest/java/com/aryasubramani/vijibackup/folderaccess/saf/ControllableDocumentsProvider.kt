package com.aryasubramani.vijibackup.folderaccess.saf

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import java.io.FileNotFoundException
import java.util.concurrent.CountDownLatch

internal data class TestDocumentRow(
    val documentId: Any? = ControllableDocumentsProvider.ROOT_DOCUMENT_ID,
    val displayName: Any? = "Test folder",
    val mimeType: Any? = DocumentsContract.Document.MIME_TYPE_DIR,
    val flags: Any? = 0,
    val size: Any? = null,
    val lastModified: Any? = null,
)

internal class ControllableDocumentsProvider : DocumentsProvider() {
    var rows: List<TestDocumentRow> = listOf(TestDocumentRow())
    var returnedColumns: Array<String>? = null
    var cursorExtras: Bundle = Bundle.EMPTY
    var queryFailure: Throwable? = null
    var returnNullCursor = false
    val childRowsByParent = mutableMapOf<String, List<TestDocumentRow>>()
    val childReturnedColumnsByParent = mutableMapOf<String, Array<String>>()
    val childExtrasByParent = mutableMapOf<String, Bundle>()
    val childFailuresByParent = mutableMapOf<String, Throwable>()
    val childNullCursorParents = mutableSetOf<String>()

    var queryCount = 0
        private set
    var rootsQueryCount = 0
        private set
    var childQueryCount = 0
        private set
    var openDocumentCallCount = 0
        private set
    var mutationCallCount = 0
        private set
    var lastDocumentIdMatchedRoot: Boolean? = null
        private set
    var lastProjection: List<String>? = null
        private set
    var lastCursor: MatrixCursor? = null
        private set
    val childParentDocumentIds = mutableListOf<String>()
    val childProjections = mutableListOf<List<String>>()
    val childCursors = mutableListOf<MatrixCursor>()
    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        rootsQueryCount += 1
        return MatrixCursor(projection?.map { it }.orEmpty().toTypedArray())
    }

    override fun queryDocument(
        documentId: String,
        projection: Array<out String>?,
    ): Cursor? {
        queryCount += 1
        lastDocumentIdMatchedRoot = documentId == ROOT_DOCUMENT_ID
        lastProjection = projection?.toList()
        queryFailure?.let(::throwQueryFailure)
        if (returnNullCursor) return null

        val columns = returnedColumns ?: projection?.map { it }?.toTypedArray() ?: DOCUMENT_PROJECTION
        return MatrixCursor(columns).also { cursor ->
            rows.forEach { row ->
                cursor.addRow(
                    columns.map { column ->
                        when (column) {
                            DocumentsContract.Document.COLUMN_DOCUMENT_ID -> row.documentId
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME -> row.displayName
                            DocumentsContract.Document.COLUMN_MIME_TYPE -> row.mimeType
                            DocumentsContract.Document.COLUMN_FLAGS -> row.flags
                            DocumentsContract.Document.COLUMN_SIZE -> row.size
                            DocumentsContract.Document.COLUMN_LAST_MODIFIED -> row.lastModified
                            else -> null
                        }
                    },
                )
            }
            cursor.extras = Bundle(cursorExtras)
            lastCursor = cursor
        }
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        childQueryCount += 1
        childParentDocumentIds += parentDocumentId
        childProjections += projection?.toList().orEmpty()
        childFailuresByParent[parentDocumentId]?.let(::throwQueryFailure)
        if (parentDocumentId in childNullCursorParents) return null

        val columns = childReturnedColumnsByParent[parentDocumentId]
            ?: projection?.map { it }?.toTypedArray()
            ?: DOCUMENT_PROJECTION
        return MatrixCursor(columns).also { cursor ->
            childRowsByParent[parentDocumentId].orEmpty().forEach { row ->
                cursor.addRow(
                    columns.map { column ->
                        when (column) {
                            DocumentsContract.Document.COLUMN_DOCUMENT_ID -> row.documentId
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME -> row.displayName
                            DocumentsContract.Document.COLUMN_MIME_TYPE -> row.mimeType
                            DocumentsContract.Document.COLUMN_FLAGS -> row.flags
                            DocumentsContract.Document.COLUMN_SIZE -> row.size
                            DocumentsContract.Document.COLUMN_LAST_MODIFIED -> row.lastModified
                            else -> null
                        }
                    },
                )
            }
            cursor.extras = Bundle(childExtrasByParent[parentDocumentId] ?: Bundle.EMPTY)
            childCursors += cursor
        }
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        documentId == ROOT_DOCUMENT_ID ||
            childRowsByParent.values.flatten().any { row -> row.documentId == documentId }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        openDocumentCallCount += 1
        throw FileNotFoundException()
    }

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String,
    ): String {
        mutationCallCount += 1
        throw UnsupportedOperationException()
    }

    override fun deleteDocument(documentId: String) {
        mutationCallCount += 1
        throw UnsupportedOperationException()
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        mutationCallCount += 1
        throw UnsupportedOperationException()
    }

    override fun moveDocument(
        sourceDocumentId: String,
        sourceParentDocumentId: String,
        targetParentDocumentId: String,
    ): String {
        mutationCallCount += 1
        throw UnsupportedOperationException()
    }

    override fun removeDocument(documentId: String, parentDocumentId: String) {
        mutationCallCount += 1
        throw UnsupportedOperationException()
    }

    override fun copyDocument(sourceDocumentId: String, targetParentDocumentId: String): String {
        mutationCallCount += 1
        throw UnsupportedOperationException()
    }

    private fun throwQueryFailure(failure: Throwable): Nothing = when (failure) {
        is FileNotFoundException -> throw failure
        is RuntimeException -> throw failure
        is Error -> throw failure
        else -> throw IllegalStateException(failure)
    }

    companion object {
        const val ROOT_DOCUMENT_ID = "root"
        val DOCUMENT_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
    }
}

internal class CancellationAwareRootProvider : ContentProvider() {
    @Volatile
    var blockQueries = false
    var queryFailure: Throwable? = null

    val queryStarted = CountDownLatch(1)
    val cancellationSignals = mutableListOf<CancellationSignal?>()

    override fun onCreate(): Boolean = true

    override fun query(
        uri: android.net.Uri,
        projection: Array<out String>?,
        queryArgs: Bundle?,
        cancellationSignal: CancellationSignal?,
    ): Cursor {
        cancellationSignals += cancellationSignal
        queryStarted.countDown()
        queryFailure?.let { throw it }
        while (blockQueries) {
            cancellationSignal?.throwIfCanceled()
            Thread.sleep(5)
        }
        val columns = projection?.map { it }?.toTypedArray()
            ?: ControllableDocumentsProvider.DOCUMENT_PROJECTION
        return MatrixCursor(columns).apply {
            addRow(
                columns.map { column ->
                    when (column) {
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID ->
                            ControllableDocumentsProvider.ROOT_DOCUMENT_ID
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME -> "Test folder"
                        DocumentsContract.Document.COLUMN_MIME_TYPE ->
                            DocumentsContract.Document.MIME_TYPE_DIR
                        DocumentsContract.Document.COLUMN_FLAGS -> 0
                        else -> null
                    }
                },
            )
        }
    }

    override fun query(
        uri: android.net.Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor = query(uri, projection, Bundle.EMPTY, null)

    override fun getType(uri: android.net.Uri): String? = null

    override fun insert(uri: android.net.Uri, values: ContentValues?): android.net.Uri? = null

    override fun delete(
        uri: android.net.Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun update(
        uri: android.net.Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
