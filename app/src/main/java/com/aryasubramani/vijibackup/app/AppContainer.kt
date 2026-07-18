package com.aryasubramani.vijibackup.app

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.room.Room
import com.aryasubramani.vijibackup.auth.config.GoogleSignInBuildConfiguration
import com.aryasubramani.vijibackup.auth.config.GoogleSignInConfiguration
import com.aryasubramani.vijibackup.auth.data.AuthSessionManager
import com.aryasubramani.vijibackup.auth.data.DataStoreAuthSessionStore
import com.aryasubramani.vijibackup.auth.data.authSessionDataStore
import com.aryasubramani.vijibackup.auth.domain.AccountAccessPolicy
import com.aryasubramani.vijibackup.auth.google.CredentialManagerCredentialStateClearer
import com.aryasubramani.vijibackup.auth.google.CredentialManagerGoogleSignInClient
import com.aryasubramani.vijibackup.auth.google.GoogleSignInClient
import com.aryasubramani.vijibackup.downloadsaccess.data.DataStoreDownloadsSourceStore
import com.aryasubramani.vijibackup.downloadsaccess.data.downloadsSourceDataStore
import com.aryasubramani.vijibackup.downloadsaccess.domain.DownloadsAccessManager
import com.aryasubramani.vijibackup.downloadsaccess.domain.DownloadsScanner
import com.aryasubramani.vijibackup.downloadsaccess.platform.AndroidDownloadsAccessProbe
import com.aryasubramani.vijibackup.downloadsaccess.platform.createAndroidDownloadsScanner
import com.aryasubramani.vijibackup.drive.config.DriveBuildConfiguration
import com.aryasubramani.vijibackup.drive.google.DriveConnectionCoordinator
import com.aryasubramani.vijibackup.drive.google.GoogleDriveAuthorizationProvider
import com.aryasubramani.vijibackup.drive.network.HttpDriveDestinationHealthProbe
import com.aryasubramani.vijibackup.drive.network.UrlConnectionDriveDestinationHttpClient
import com.aryasubramani.vijibackup.folderaccess.data.RoomFolderMappingRepository
import com.aryasubramani.vijibackup.folderaccess.data.DataStoreSignOutCleanupIntentStore
import com.aryasubramani.vijibackup.folderaccess.data.signOutCleanupIntentDataStore
import com.aryasubramani.vijibackup.folderaccess.data.db.VijiBackupDatabase
import com.aryasubramani.vijibackup.folderaccess.domain.FolderMappingRepository
import com.aryasubramani.vijibackup.folderaccess.saf.ContentResolverLocalFolderAccessValidator
import com.aryasubramani.vijibackup.folderaccess.saf.ContentResolverLocalFolderDocumentSource
import com.aryasubramani.vijibackup.folderaccess.saf.ContentResolverLocalFolderGrantManager
import com.aryasubramani.vijibackup.folderaccess.saf.ContentResolverLocalFolderMetadataReader
import com.aryasubramani.vijibackup.folderaccess.saf.IterativeLocalFolderScanner

internal interface AppContainer {
    val authSessionManager: AuthSessionManager
    val googleSignInClient: GoogleSignInClient
    val folderMappingRepository: FolderMappingRepository
    val downloadsAccessManager: DownloadsAccessManager
    val downloadsScanner: DownloadsScanner
    val driveConnectionCoordinator: DriveConnectionCoordinator
    val isGoogleSignInConfigured: Boolean
}

internal class DefaultAppContainer(context: Context) : AppContainer {
    private val applicationContext = context.applicationContext
    private val credentialManager = CredentialManager.create(applicationContext)
    private val googleSignInConfiguration = GoogleSignInBuildConfiguration.value
    private val downloadsAccessProbe = AndroidDownloadsAccessProbe()
    private val folderAccessDatabase by lazy {
        Room.databaseBuilder(
            applicationContext,
            VijiBackupDatabase::class.java,
            VijiBackupDatabase.DATABASE_NAME,
        ).build()
    }

    override val isGoogleSignInConfigured =
        googleSignInConfiguration is GoogleSignInConfiguration.Ready

    override val authSessionManager = AuthSessionManager(
        accessPolicy = AccountAccessPolicy(
            allowedEmails = when (val configuration = googleSignInConfiguration) {
                is GoogleSignInConfiguration.Ready -> configuration.allowedAccounts
                GoogleSignInConfiguration.Invalid -> emptySet()
            },
        ),
        sessionStore = DataStoreAuthSessionStore(applicationContext.authSessionDataStore),
        credentialStateClearer = CredentialManagerCredentialStateClearer(credentialManager),
    )

    override val googleSignInClient: GoogleSignInClient =
        CredentialManagerGoogleSignInClient(
            credentialManager = credentialManager,
            webClientId = when (val configuration = googleSignInConfiguration) {
                is GoogleSignInConfiguration.Ready -> configuration.webClientId
                GoogleSignInConfiguration.Invalid -> ""
            },
        )

    override val folderMappingRepository: FolderMappingRepository by lazy {
        RoomFolderMappingRepository(
            dao = folderAccessDatabase.folderAccessDao(),
            signOutCleanupIntentStore = DataStoreSignOutCleanupIntentStore(
                applicationContext.signOutCleanupIntentDataStore,
            ),
            grantManager = ContentResolverLocalFolderGrantManager(
                contentResolver = applicationContext.contentResolver,
            ),
            metadataReader = ContentResolverLocalFolderMetadataReader(
                contentResolver = applicationContext.contentResolver,
            ),
            accessValidator = ContentResolverLocalFolderAccessValidator(
                contentResolver = applicationContext.contentResolver,
            ),
            scanner = IterativeLocalFolderScanner(
                documentSource = ContentResolverLocalFolderDocumentSource(
                    contentResolver = applicationContext.contentResolver,
                ),
            ),
        )
    }

    override val downloadsAccessManager = DownloadsAccessManager(
        store = DataStoreDownloadsSourceStore(applicationContext.downloadsSourceDataStore),
        accessProbe = downloadsAccessProbe,
    )

    override val downloadsScanner = createAndroidDownloadsScanner(downloadsAccessProbe)

    override val driveConnectionCoordinator by lazy {
        DriveConnectionCoordinator(
            authorizationProvider = GoogleDriveAuthorizationProvider(applicationContext),
            destinationProbe = HttpDriveDestinationHealthProbe(
                configuration = DriveBuildConfiguration.value,
                httpClient = UrlConnectionDriveDestinationHttpClient(),
            ),
        )
    }
}
