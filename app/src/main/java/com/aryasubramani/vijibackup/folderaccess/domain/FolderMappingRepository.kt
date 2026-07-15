package com.aryasubramani.vijibackup.folderaccess.domain

import kotlinx.coroutines.flow.Flow

data class FolderMapping(
    val id: String,
    val displayName: String?,
    val enabled: Boolean,
)

data class FolderPickerLaunch(
    val requestToken: String,
    val initialTreeUri: String?,
)

sealed interface BeginFolderPickerResult {
    data class Started(val request: FolderPickerLaunch) : BeginFolderPickerResult
    data object Busy : BeginFolderPickerResult
    data object MappingNotFound : BeginFolderPickerResult
    data object StorageFailure : BeginFolderPickerResult
}

sealed interface FolderPickerSelection {
    data class Selected(
        val treeUri: String,
        val grantedFlags: Int,
    ) : FolderPickerSelection

    data object Cancelled : FolderPickerSelection
}

sealed interface FolderPickerCompletion {
    data class Added(val mappingId: String) : FolderPickerCompletion
    data class Repaired(val mappingId: String) : FolderPickerCompletion
    data object Cancelled : FolderPickerCompletion
    data object Stale : FolderPickerCompletion
    data object InvalidSelection : FolderPickerCompletion
    data object ReadPermissionMissing : FolderPickerCompletion
    data object Duplicate : FolderPickerCompletion
    data object GrantFailure : FolderPickerCompletion
    data object StorageFailure : FolderPickerCompletion
    data object CleanupIncomplete : FolderPickerCompletion
}

sealed interface RemoveFolderResult {
    data object Removed : RemoveFolderResult
    data object MappingNotFound : RemoveFolderResult
    data object Busy : RemoveFolderResult
    data object GrantFailure : RemoveFolderResult
    data object StorageFailure : RemoveFolderResult
}

sealed interface BeginFolderScanResult {
    data class Ready(val events: Flow<FolderScanEvent>) : BeginFolderScanResult
    data class AccessUnavailable(
        val health: FolderAccessHealth,
    ) : BeginFolderScanResult
    data object MappingNotFound : BeginFolderScanResult
    data object StorageFailure : BeginFolderScanResult
}

enum class SetFolderEnabledResult {
    Updated,
    MappingNotFound,
    StorageFailure,
}

enum class PendingFolderCleanupResult {
    Complete,
    RetryRequired,
}

interface FolderMappingRepository {
    fun observeMappings(): Flow<List<FolderMapping>>
    suspend fun beginAdd(): BeginFolderPickerResult
    suspend fun beginRepair(mappingId: String): BeginFolderPickerResult
    suspend fun completePicker(
        requestToken: String,
        selection: FolderPickerSelection,
    ): FolderPickerCompletion

    suspend fun prepareForSignOut(): PendingFolderCleanupResult

    suspend fun validate(mappingId: String): ValidateFolderAccessResult

    suspend fun beginScan(mappingId: String): BeginFolderScanResult

    suspend fun setEnabled(mappingId: String, enabled: Boolean): SetFolderEnabledResult

    suspend fun remove(mappingId: String): RemoveFolderResult
}
