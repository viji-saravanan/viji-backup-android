package com.aryasubramani.vijibackup.folderaccess.saf

import android.app.AuthenticationRequiredException
import android.content.ContentResolver
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.provider.DocumentsContract
import androidx.annotation.RequiresApi
import com.aryasubramani.vijibackup.folderaccess.domain.FolderAccessHealth
import com.aryasubramani.vijibackup.folderaccess.domain.LocalFolderAccessValidator
import java.io.FileNotFoundException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

internal fun interface PersistedReadGrantLookup {
    /** Returns null when Android's persisted-permission state cannot be read. */
    fun hasExactReadGrant(treeUri: Uri): Boolean?
}

internal class ContentResolverLocalFolderAccessValidator(
    private val contentResolver: ContentResolver,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val persistedReadGrantLookup: PersistedReadGrantLookup =
        PersistedReadGrantLookup { treeUri ->
            contentResolver.persistedUriPermissions
                .firstOrNull { permission -> permission.uri == treeUri }
                ?.isReadPermission == true
        },
) : LocalFolderAccessValidator {
    override suspend fun validate(treeUri: String): FolderAccessHealth =
        withContext(ioDispatcher) {
            val parsedTreeUri = treeUri.toValidTreeUri()
                ?: return@withContext FolderAccessHealth.TemporarilyUnavailable
            when (readGrantStatus(parsedTreeUri)) {
                ReadGrantStatus.Missing -> return@withContext FolderAccessHealth.PermissionMissing
                ReadGrantStatus.Unavailable ->
                    return@withContext FolderAccessHealth.TemporarilyUnavailable
                ReadGrantStatus.Present -> Unit
            }
            queryRootCancellable(parsedTreeUri)
        }

    private suspend fun queryRootCancellable(treeUri: Uri): FolderAccessHealth =
        suspendCancellableCoroutine { continuation ->
            val cancellationSignal = CancellationSignal()
            continuation.invokeOnCancellation { cancellationSignal.cancel() }
            val health = try {
                queryRoot(treeUri, cancellationSignal)
            } catch (cancelled: CancellationException) {
                if (continuation.isActive) {
                    continuation.cancel(cancelled)
                }
                return@suspendCancellableCoroutine
            } catch (error: Exception) {
                if (!continuation.isActive) return@suspendCancellableCoroutine
                classifyQueryFailure(error, treeUri)
            }
            continuation.resume(health) { _, _, _ -> }
        }

    private fun queryRoot(
        treeUri: Uri,
        cancellationSignal: CancellationSignal,
    ): FolderAccessHealth {
        val expectedDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
        val documentUri = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            expectedDocumentId,
        )
        val cursor = contentResolver.query(
            documentUri,
            DOCUMENT_PROJECTION,
            null,
            null,
            null,
            cancellationSignal,
        ) ?: return FolderAccessHealth.TemporarilyUnavailable

        return cursor.use {
            val extras = it.extras
            if (extras.getBoolean(DocumentsContract.EXTRA_LOADING, false) ||
                extras.containsKey(DocumentsContract.EXTRA_ERROR)
            ) {
                return@use FolderAccessHealth.TemporarilyUnavailable
            }

            val documentIdIndex = it.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val displayNameIndex = it.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeTypeIndex = it.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val flagsIndex = it.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS)
            val sizeIndex = it.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val lastModifiedIndex = it.getColumnIndex(
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            )
            if (listOf(
                    documentIdIndex,
                    displayNameIndex,
                    mimeTypeIndex,
                    flagsIndex,
                    sizeIndex,
                    lastModifiedIndex,
                ).any { index -> index < 0 }
            ) {
                return@use FolderAccessHealth.TemporarilyUnavailable
            }
            if (!it.moveToFirst()) return@use FolderAccessHealth.TreeMissing
            if (it.isNull(documentIdIndex) ||
                it.isNull(displayNameIndex) ||
                it.isNull(mimeTypeIndex) ||
                it.isNull(flagsIndex)
            ) {
                return@use FolderAccessHealth.TemporarilyUnavailable
            }

            val documentId = it.getString(documentIdIndex)
            val displayName = it.getString(displayNameIndex)
            val mimeType = it.getString(mimeTypeIndex)
            it.getInt(flagsIndex)
            if (it.moveToNext() ||
                documentId != expectedDocumentId ||
                displayName.isNullOrBlank() ||
                mimeType != DocumentsContract.Document.MIME_TYPE_DIR
            ) {
                FolderAccessHealth.TemporarilyUnavailable
            } else {
                FolderAccessHealth.Ready
            }
        }
    }

    private fun classifyQueryFailure(error: Exception, treeUri: Uri): FolderAccessHealth = when {
        error.isAuthenticationRequired() -> FolderAccessHealth.ProviderAuthRequired
        error is FileNotFoundException -> FolderAccessHealth.TreeMissing
        error is SecurityException -> when (readGrantStatus(treeUri)) {
            ReadGrantStatus.Missing -> FolderAccessHealth.PermissionMissing
            ReadGrantStatus.Present,
            ReadGrantStatus.Unavailable,
            -> FolderAccessHealth.TemporarilyUnavailable
        }
        else -> FolderAccessHealth.TemporarilyUnavailable
    }

    private fun readGrantStatus(treeUri: Uri): ReadGrantStatus = try {
        when (persistedReadGrantLookup.hasExactReadGrant(treeUri)) {
            true -> ReadGrantStatus.Present
            false -> ReadGrantStatus.Missing
            null -> ReadGrantStatus.Unavailable
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        ReadGrantStatus.Unavailable
    }

    private fun String.toValidTreeUri(): Uri? = try {
        Uri.parse(this).takeIf { uri ->
            uri.scheme == ContentResolver.SCHEME_CONTENT && DocumentsContract.isTreeUri(uri)
        }
    } catch (_: Exception) {
        null
    }

    private fun Exception.isAuthenticationRequired(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && Api26Impl.isAuthenticationRequired(this)

    private enum class ReadGrantStatus {
        Present,
        Missing,
        Unavailable,
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private object Api26Impl {
        fun isAuthenticationRequired(error: Exception): Boolean =
            error is AuthenticationRequiredException
    }

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
