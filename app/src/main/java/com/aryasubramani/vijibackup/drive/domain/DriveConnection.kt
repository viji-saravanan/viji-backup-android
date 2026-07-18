package com.aryasubramani.vijibackup.drive.domain

internal const val DRIVE_AUTHORIZATION_SCOPE = "https://www.googleapis.com/auth/drive"

internal enum class DriveConnectionResult {
    ConfigurationRequired,
    NeedsAuthorization,
    AccountMismatch,
    DestinationMissingOrInaccessible,
    DestinationNotFolder,
    DestinationTrashed,
    DestinationReadOnly,
    DestinationQuotaExceeded,
    TemporaryFailure,
    ProviderUnavailable,
    InvalidResponse,
    Ready,
}

internal val DriveConnectionResult.isReady: Boolean
    get() = this == DriveConnectionResult.Ready

internal val DriveConnectionResult.isAutomaticRetryCandidate: Boolean
    get() = this == DriveConnectionResult.TemporaryFailure
