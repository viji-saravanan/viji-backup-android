package com.aryasubramani.vijibackup.folderaccess.domain

enum class FolderAccessHealth {
    Checking,
    Ready,
    PermissionMissing,
    TreeMissing,
    ProviderAuthRequired,
    TemporarilyUnavailable,
}

sealed interface ValidateFolderAccessResult {
    data class Found(val health: FolderAccessHealth) : ValidateFolderAccessResult
    data object MappingNotFound : ValidateFolderAccessResult
    data object StorageFailure : ValidateFolderAccessResult
}
