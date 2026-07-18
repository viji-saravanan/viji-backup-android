package com.aryasubramani.vijibackup.downloadsaccess.domain

import kotlinx.coroutines.flow.Flow

data class DownloadsScanProgress(
    val foldersVisited: Long = 0,
    val filesDiscovered: Long = 0,
    val knownBytes: Long = 0,
    val filesWithUnknownSize: Long = 0,
    val unreadableEntries: Long = 0,
)

data class DownloadsScanSummary(
    val progress: DownloadsScanProgress,
    val elapsedTimeMillis: Long,
    val issues: Set<DownloadsScanIssue>,
)

enum class DownloadsScanIssue {
    RootUnavailable,
    PermissionLost,
    DirectoryUnreadable,
    EntryUnreadable,
    SymbolicLinkSkipped,
    RootEscapeRejected,
    RepeatedEntry,
}

sealed interface DownloadsScanEvent {
    data class Progress(val value: DownloadsScanProgress) : DownloadsScanEvent

    data class Complete(val summary: DownloadsScanSummary) : DownloadsScanEvent

    data class Partial(val summary: DownloadsScanSummary) : DownloadsScanEvent

    data class Failed(val summary: DownloadsScanSummary) : DownloadsScanEvent
}

fun interface DownloadsScanner {
    fun scan(): Flow<DownloadsScanEvent>
}
