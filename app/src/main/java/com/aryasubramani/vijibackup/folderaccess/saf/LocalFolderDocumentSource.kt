package com.aryasubramani.vijibackup.folderaccess.saf

import com.aryasubramani.vijibackup.folderaccess.domain.FolderScanIssue

internal interface LocalFolderDocumentSource {
    fun rootDocumentId(treeUri: String): String?

    suspend fun queryChildren(
        treeUri: String,
        parentDocumentId: String,
        onDocument: suspend (FolderDocumentMetadata) -> Unit,
    ): FolderDocumentQueryResult
}

internal data class FolderDocumentMetadata(
    val documentId: String,
    val isDirectory: Boolean,
    val size: Long?,
)

internal data class FolderDocumentQueryResult(
    val cursorReturned: Boolean,
    val unreadableEntries: Long = 0,
    val issues: Set<FolderScanIssue> = emptySet(),
)
