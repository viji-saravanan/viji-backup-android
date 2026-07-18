package com.aryasubramani.vijibackup.downloadsaccess.platform

import com.aryasubramani.vijibackup.downloadsaccess.domain.DownloadsScanIssue

sealed interface DownloadsRootResult {
    data class Found(val rootId: String) : DownloadsRootResult

    data object PermissionMissing : DownloadsRootResult

    data object Unavailable : DownloadsRootResult
}

data class DownloadsTreeEntry(
    val id: String,
    val isDirectory: Boolean,
    val size: Long?,
)

data class DownloadsDirectoryReadResult(
    val directoryRead: Boolean,
    val unreadableEntries: Long = 0,
    val issues: Set<DownloadsScanIssue> = emptySet(),
)

interface DownloadsTreeSource {
    fun root(): DownloadsRootResult

    suspend fun readChildren(
        parentId: String,
        onEntry: suspend (DownloadsTreeEntry) -> Unit,
    ): DownloadsDirectoryReadResult
}
