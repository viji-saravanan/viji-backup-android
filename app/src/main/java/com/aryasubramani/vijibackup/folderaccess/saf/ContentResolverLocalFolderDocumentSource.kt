package com.aryasubramani.vijibackup.folderaccess.saf

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.provider.DocumentsContract
import com.aryasubramani.vijibackup.folderaccess.domain.FolderScanIssue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resumeWithException

internal class ContentResolverLocalFolderDocumentSource(
    private val contentResolver: ContentResolver,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : LocalFolderDocumentSource {
    override fun rootDocumentId(treeUri: String): String? = treeUri.toValidTreeUri()
        ?.let { uri -> runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() }
        ?.takeIf(String::isNotBlank)

    override suspend fun queryChildren(
        treeUri: String,
        parentDocumentId: String,
        onDocument: suspend (FolderDocumentMetadata) -> Unit,
    ): FolderDocumentQueryResult = withContext(ioDispatcher) {
        val parsedTreeUri = treeUri.toValidTreeUri()
            ?: return@withContext FolderDocumentQueryResult(
                cursorReturned = false,
                issues = setOf(FolderScanIssue.InvalidTree),
            )
        val childrenUri = try {
            DocumentsContract.buildChildDocumentsUriUsingTree(
                parsedTreeUri,
                parentDocumentId,
            )
        } catch (_: Exception) {
            return@withContext FolderDocumentQueryResult(
                cursorReturned = false,
                issues = setOf(FolderScanIssue.QueryFailure),
            )
        }

        val cursor = try {
            queryCancellable(childrenUri)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return@withContext FolderDocumentQueryResult(
                cursorReturned = false,
                issues = setOf(FolderScanIssue.QueryFailure),
            )
        } ?: return@withContext FolderDocumentQueryResult(
            cursorReturned = false,
            issues = setOf(FolderScanIssue.NullCursor),
        )

        readChildren(cursor, onDocument)
    }

    private suspend fun queryCancellable(childrenUri: Uri): Cursor? =
        suspendCancellableCoroutine { continuation ->
            val cancellationSignal = CancellationSignal()
            continuation.invokeOnCancellation { cancellationSignal.cancel() }
            try {
                val cursor = contentResolver.query(
                    childrenUri,
                    DOCUMENT_PROJECTION,
                    null,
                    null,
                    null,
                    cancellationSignal,
                )
                continuation.resume(cursor) { _, abandonedCursor, _ ->
                    abandonedCursor?.close()
                }
            } catch (cancelled: OperationCanceledException) {
                if (continuation.isActive) {
                    continuation.cancel(
                        CancellationException("Folder query cancelled").apply {
                            initCause(cancelled)
                        },
                    )
                }
            } catch (cancelled: CancellationException) {
                if (continuation.isActive) continuation.cancel(cancelled)
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }

    private suspend fun readChildren(
        cursor: Cursor,
        onDocument: suspend (FolderDocumentMetadata) -> Unit,
    ): FolderDocumentQueryResult {
        val issues = linkedSetOf<FolderScanIssue>()
        var unreadableEntries = 0L
        try {
            val extrasReadable = try {
                val extras = cursor.extras
                if (extras.getBoolean(DocumentsContract.EXTRA_LOADING, false)) {
                    issues += FolderScanIssue.ProviderLoading
                }
                if (extras.containsKey(DocumentsContract.EXTRA_ERROR)) {
                    issues += FolderScanIssue.ProviderError
                }
                true
            } catch (_: Exception) {
                issues += FolderScanIssue.QueryFailure
                false
            }

            val columns = CursorColumns.from(cursor)
            if (columns == null) {
                unreadableEntries = unreadableEntries.saturatedIncrement()
                issues += FolderScanIssue.MalformedEntry
            } else if (extrasReadable) {
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val hasNext = try {
                        cursor.moveToNext()
                    } catch (cancelled: OperationCanceledException) {
                        throw CancellationException("Folder query cancelled").apply {
                            initCause(cancelled)
                        }
                    } catch (_: Exception) {
                        unreadableEntries = unreadableEntries.saturatedIncrement()
                        issues += FolderScanIssue.QueryFailure
                        false
                    }
                    if (!hasNext) break

                    val metadata = try {
                        columns.read(cursor)
                    } catch (cancelled: OperationCanceledException) {
                        throw CancellationException("Folder query cancelled").apply {
                            initCause(cancelled)
                        }
                    } catch (_: Exception) {
                        null
                    }
                    if (metadata == null) {
                        unreadableEntries = unreadableEntries.saturatedIncrement()
                        issues += FolderScanIssue.MalformedEntry
                    } else {
                        onDocument(metadata)
                    }
                }
            }
        } finally {
            try {
                cursor.close()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                issues += FolderScanIssue.QueryFailure
            }
        }

        return FolderDocumentQueryResult(
            cursorReturned = true,
            unreadableEntries = unreadableEntries,
            issues = issues,
        )
    }

    private fun String.toValidTreeUri(): Uri? = try {
        Uri.parse(this).takeIf { uri ->
            uri.scheme == ContentResolver.SCHEME_CONTENT && DocumentsContract.isTreeUri(uri)
        }
    } catch (_: Exception) {
        null
    }

    private data class CursorColumns(
        val documentId: Int,
        val displayName: Int,
        val mimeType: Int,
        val flags: Int,
        val size: Int,
        val lastModified: Int,
    ) {
        fun read(cursor: Cursor): FolderDocumentMetadata? {
            if (cursor.isNull(documentId) ||
                cursor.isNull(displayName) ||
                cursor.isNull(mimeType) ||
                cursor.isNull(flags)
            ) {
                return null
            }
            val id = cursor.getString(documentId)?.takeIf(String::isNotBlank) ?: return null
            if (cursor.getString(displayName).isNullOrBlank()) return null
            val type = cursor.getString(mimeType)?.takeIf(String::isNotBlank) ?: return null
            cursor.getInt(flags)
            val documentSize = if (cursor.isNull(size)) null else cursor.getLong(size)
            if (documentSize != null && documentSize < 0) return null
            if (!cursor.isNull(lastModified) && cursor.getLong(lastModified) < 0) return null
            return FolderDocumentMetadata(
                documentId = id,
                isDirectory = type == DocumentsContract.Document.MIME_TYPE_DIR,
                size = documentSize,
            )
        }

        companion object {
            fun from(cursor: Cursor): CursorColumns? {
                val columns = CursorColumns(
                    documentId = cursor.getColumnIndex(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    ),
                    displayName = cursor.getColumnIndex(
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    ),
                    mimeType = cursor.getColumnIndex(
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                    ),
                    flags = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS),
                    size = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE),
                    lastModified = cursor.getColumnIndex(
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                    ),
                )
                return columns.takeIf {
                    listOf(
                        it.documentId,
                        it.displayName,
                        it.mimeType,
                        it.flags,
                        it.size,
                        it.lastModified,
                    ).none { index -> index < 0 }
                }
            }
        }
    }

    private fun Long.saturatedIncrement(): Long =
        if (this == Long.MAX_VALUE) Long.MAX_VALUE else this + 1

    private companion object {
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
