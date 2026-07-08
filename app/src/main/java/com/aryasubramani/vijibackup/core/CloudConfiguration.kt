package com.aryasubramani.vijibackup.core

object CloudConfiguration {
    const val driveFileScope = "https://www.googleapis.com/auth/drive.file"
    const val driveUploadFolderId = "<private-drive-upload-folder-id>"
    const val driveUploadFolderOwnerEmail = "owner.primary@example.test"

    const val internalDebugAndroidOAuthClientId =
        "<private-internal-android-oauth-client-id>"
    const val publicDebugAndroidOAuthClientId =
        "<private-public-android-oauth-client-id>"

    const val emailSender = "owner.primary@example.test"
    const val emailMethod = "google-apps-script-mailapp-relay"

    val allowedGoogleAccounts = setOf(
        "owner.primary@example.test",
        "owner.alternate@example.test",
        "primary.user@example.test",
        "alternate.user@example.test",
    )

    val completionEmailRecipients = allowedGoogleAccounts

    fun isAllowedGoogleAccount(email: String): Boolean =
        email.trim().lowercase() in allowedGoogleAccounts

    fun androidOAuthClientIdForApplicationId(applicationId: String): String? =
        when (applicationId) {
            AppIdentity.internalApplicationId -> internalDebugAndroidOAuthClientId
            AppIdentity.baseApplicationId -> publicDebugAndroidOAuthClientId
            else -> null
        }
}
