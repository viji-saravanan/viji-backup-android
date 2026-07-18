package com.aryasubramani.vijibackup.folderaccess.domain

import kotlinx.coroutines.flow.Flow

data class FolderScanProgress(
    val foldersVisited: Long = 0,
    val filesDiscovered: Long = 0,
    val knownBytes: Long = 0,
    val filesWithUnknownSize: Long = 0,
    val unreadableEntries: Long = 0,
)

data class FolderScanSummary(
    val progress: FolderScanProgress,
    val elapsedTimeMillis: Long,
    val issues: Set<FolderScanIssue>,
)

enum class FolderScanIssue {
    InvalidTree,
    NullCursor,
    ProviderLoading,
    ProviderError,
    QueryFailure,
    MalformedEntry,
    RepeatedDocument,
}

sealed interface FolderScanEvent {
    data class Progress(val value: FolderScanProgress) : FolderScanEvent

    data class Complete(val summary: FolderScanSummary) : FolderScanEvent

    data class Partial(val summary: FolderScanSummary) : FolderScanEvent

    data class Failed(val summary: FolderScanSummary) : FolderScanEvent
}

fun interface LocalFolderScanner {
    fun scan(treeUri: String): Flow<FolderScanEvent>
}
