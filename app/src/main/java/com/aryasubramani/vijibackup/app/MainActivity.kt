package com.aryasubramani.vijibackup.app

import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.aryasubramani.vijibackup.auth.presentation.AuthUiState
import com.aryasubramani.vijibackup.auth.presentation.AuthViewModel
import com.aryasubramani.vijibackup.downloadsaccess.platform.AndroidDownloadsAccessSettingsIntentFactory
import com.aryasubramani.vijibackup.downloadsaccess.presentation.DownloadsAccessViewModel
import com.aryasubramani.vijibackup.drive.domain.DriveConnectionResult
import com.aryasubramani.vijibackup.drive.google.DriveConnectionStep
import com.aryasubramani.vijibackup.drive.presentation.DriveConnectionRequest
import com.aryasubramani.vijibackup.drive.presentation.DriveConnectionRequestMode
import com.aryasubramani.vijibackup.drive.presentation.DriveConnectionViewModel
import com.aryasubramani.vijibackup.folderaccess.domain.FolderPickerLaunch
import com.aryasubramani.vijibackup.folderaccess.domain.FolderPickerSelection
import com.aryasubramani.vijibackup.folderaccess.presentation.FolderAccessViewModel
import com.aryasubramani.vijibackup.folderaccess.saf.FolderPickerResult
import com.aryasubramani.vijibackup.folderaccess.saf.ReadOnlyFolderPickerRequest
import com.aryasubramani.vijibackup.folderaccess.saf.ReadOnlyOpenDocumentTreeContract
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var folderPickerRequestState: FolderPickerRequestState
    private val folderPickerContract = ReadOnlyOpenDocumentTreeContract()
    private val folderPickerLaunchers = mutableMapOf<
        String,
        ActivityResultLauncher<ReadOnlyFolderPickerRequest>,
    >()
    private val folderPickerActivityResultRegistry =
        folderPickerActivityResultRegistryFactoryForTesting?.invoke() ?: activityResultRegistry
    private val mainHandler = Handler(Looper.getMainLooper())
    private var driveAuthorizationCompletionJob: Job? = null

    @VisibleForTesting
    internal var protectedWindowPolicyAppliedForTesting = false
        private set

    private val appContainer: AppContainer
        get() = (application as VijiBackupApplication).appContainer

    private val authViewModel by viewModels<AuthViewModel> {
        AuthViewModel.Factory(
            sessionManager = appContainer.authSessionManager,
            isGoogleSignInConfigured = appContainer.isGoogleSignInConfigured,
            prepareForSignOut = appContainer.folderMappingRepository::prepareForSignOut,
        )
    }
    private val folderAccessViewModel by viewModels<FolderAccessViewModel> {
        FolderAccessViewModel.Factory(appContainer.folderMappingRepository)
    }
    private val downloadsAccessViewModel by viewModels<DownloadsAccessViewModel> {
        DownloadsAccessViewModel.Factory(
            manager = appContainer.downloadsAccessManager,
            scanner = appContainer.downloadsScanner,
        )
    }
    private val driveConnectionViewModel by viewModels<DriveConnectionViewModel>()
    private val downloadsSettingsIntentFactory by lazy(LazyThreadSafetyMode.NONE) {
        AndroidDownloadsAccessSettingsIntentFactory(this)
    }
    private val downloadsSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        downloadsAccessViewModel.onSettingsResult()
    }
    private val driveAuthorizationLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        handleDriveAuthorizationActivityResult(
            resultCode = result.resultCode,
            data = result.data,
        )
    }
    private val credentialRequestDispatcher by lazy(LazyThreadSafetyMode.NONE) {
        AuthCredentialRequestDispatcher(
            coroutineScope = lifecycleScope,
            signIn = { mode ->
                appContainer.googleSignInClient.signIn(
                    activityContext = this@MainActivity,
                    mode = mode,
                )
            },
            onResult = authViewModel::onSignInResult,
            onInterrupted = authViewModel::onSignInInterrupted,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        folderPickerRequestState = FolderPickerRequestState.restore(savedInstanceState)
        if (folderPickerActivityResultRegistry !== activityResultRegistry) {
            folderPickerActivityResultRegistry.onRestoreInstanceState(
                savedInstanceState?.getBundle(FOLDER_PICKER_REGISTRY_STATE_KEY),
            )
        }
        super.onCreate(savedInstanceState)
        folderPickerRequestState.outstandingLaunches.forEach(::registerFolderPicker)
        applyProtectedWindowPolicy()
        enableEdgeToEdge()
        setContent {
            val uiState by authViewModel.uiState.collectAsStateWithLifecycle()
            val folderAccessUiState by folderAccessViewModel.uiState.collectAsStateWithLifecycle()
            val downloadsAccessUiState by
                downloadsAccessViewModel.uiState.collectAsStateWithLifecycle()
            val driveConnectionUiState by
                driveConnectionViewModel.uiState.collectAsStateWithLifecycle()
            val signInRequest = (uiState as? AuthUiState.SigningIn)?.request
            val approvedAccount = (uiState as? AuthUiState.Approved)?.account

            LaunchedEffect(uiState) {
                if (uiState is AuthUiState.ReauthenticationRequired) {
                    authViewModel.startAutomaticReauthentication()
                }
                if (approvedAccount != null) {
                    folderAccessViewModel.activate()
                    downloadsAccessViewModel.activate()
                    driveConnectionViewModel.activate(approvedAccount)
                } else {
                    folderAccessViewModel.deactivate()
                    downloadsAccessViewModel.deactivate()
                    driveConnectionViewModel.deactivate()
                }
            }
            LaunchedEffect(approvedAccount) {
                val account = approvedAccount ?: return@LaunchedEffect
                driveConnectionViewModel.requests.collectLatest { request ->
                    if (request.account == account) {
                        executeDriveConnectionRequest(request)
                    }
                }
            }
            LaunchedEffect(signInRequest?.id) {
                val request = signInRequest ?: return@LaunchedEffect
                val mode = authViewModel.consumeSignInRequest(request.id)
                    ?: return@LaunchedEffect
                credentialRequestDispatcher.dispatch(request.id, mode)
            }
            LaunchedEffect(Unit) {
                folderAccessViewModel.pickerLaunches.collect { request ->
                    if (authViewModel.uiState.value is AuthUiState.Approved) {
                        launchFolderPicker(request)
                    }
                }
            }
            LaunchedEffect(Unit) {
                downloadsAccessViewModel.settingsLaunches.collect { request ->
                    if (authViewModel.uiState.value !is AuthUiState.Approved) {
                        downloadsAccessViewModel.onSettingsLaunchFailed(request.id)
                        return@collect
                    }
                    val intent = downloadsSettingsIntentFactory.create()
                    if (intent == null) {
                        downloadsAccessViewModel.onSettingsLaunchFailed(request.id)
                        return@collect
                    }
                    try {
                        downloadsSettingsLauncher.launch(intent)
                    } catch (_: Exception) {
                        downloadsAccessViewModel.onSettingsLaunchFailed(request.id)
                    }
                }
            }

            VijiBackupApp(
                uiState = uiState,
                folderAccessUiState = folderAccessUiState,
                downloadsAccessUiState = downloadsAccessUiState,
                driveConnectionUiState = driveConnectionUiState,
                onSignIn = authViewModel::signIn,
                onRetry = authViewModel::retry,
                onSignOut = ::signOut,
                onChangeAccount = ::changeAccount,
                onAddFolder = folderAccessViewModel::addFolder,
                onRepairFolder = folderAccessViewModel::repairFolder,
                onRemoveFolder = folderAccessViewModel::removeFolder,
                onSetFolderEnabled = folderAccessViewModel::setFolderEnabled,
                onScanFolder = folderAccessViewModel::scanFolder,
                onCancelScan = folderAccessViewModel::cancelScan,
                onRequestDownloadsAccess = downloadsAccessViewModel::requestAccess,
                onReviewDownloadsPermission = downloadsAccessViewModel::reviewPermission,
                onSetDownloadsEnabled = downloadsAccessViewModel::setEnabled,
                onRemoveDownloads = downloadsAccessViewModel::remove,
                onRefreshDownloads = downloadsAccessViewModel::refresh,
                onScanDownloads = downloadsAccessViewModel::scan,
                onCancelDownloadsScan = downloadsAccessViewModel::cancelScan,
                onConnectDrive = driveConnectionViewModel::connect,
                onRefreshDrive = driveConnectionViewModel::refresh,
            )
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        folderPickerRequestState.saveTo(outState)
        if (folderPickerActivityResultRegistry !== activityResultRegistry) {
            outState.putBundle(
                FOLDER_PICKER_REGISTRY_STATE_KEY,
                Bundle().also(folderPickerActivityResultRegistry::onSaveInstanceState),
            )
        }
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        authViewModel.onAppForegrounded()
        folderAccessViewModel.refreshFolderHealth()
        downloadsAccessViewModel.refresh()
    }

    override fun onStop() {
        if (!isChangingConfigurations) {
            authViewModel.onAppBackgrounded()
        }
        super.onStop()
    }

    override fun onDestroy() {
        folderPickerLaunchers.values.forEach(ActivityResultLauncher<*>::unregister)
        folderPickerLaunchers.clear()
        super.onDestroy()
    }

    private fun launchFolderPicker(request: FolderPickerLaunch) {
        val registration = folderPickerRequestState.stageForLaunch(request.requestToken) ?: return
        val initialUri = request.initialTreeUri?.let { rawUri ->
            try {
                Uri.parse(rawUri)
            } catch (_: Exception) {
                null
            }
        }
        val launcher = registerFolderPicker(registration)
        try {
            launcher.launch(ReadOnlyFolderPickerRequest(initialUri = initialUri))
        } catch (_: Exception) {
            folderPickerLaunchers.remove(registration.registryKey)?.unregister()
            val requestToken = folderPickerRequestState.consumeResult(registration.registryKey)
                ?: return
            completeFolderPicker(requestToken, FolderPickerResult.Cancelled)
        }
    }

    private fun registerFolderPicker(
        registration: FolderPickerLaunchRegistration,
    ): ActivityResultLauncher<ReadOnlyFolderPickerRequest> {
        folderPickerLaunchers[registration.registryKey]?.let { return it }
        val launcher = folderPickerActivityResultRegistry.register(
            registration.registryKey,
            folderPickerContract,
        ) { result ->
            handleFolderPickerResult(registration.registryKey, result)
        }
        if (folderPickerRequestState.hasLaunch(registration.registryKey)) {
            folderPickerLaunchers[registration.registryKey] = launcher
        } else {
            launcher.unregister()
        }
        return launcher
    }

    private fun handleFolderPickerResult(registryKey: String, result: FolderPickerResult) {
        val requestToken = folderPickerRequestState.consumeResult(registryKey)
        folderPickerLaunchers.remove(registryKey)?.let { launcher ->
            // ActivityResultRegistry removes its in-flight marker after this callback returns.
            mainHandler.post(launcher::unregister)
        }
        if (requestToken == null) return
        completeFolderPicker(requestToken, result)
    }

    private fun completeFolderPicker(requestToken: String, result: FolderPickerResult) {
        lifecycleScope.launch {
            folderAccessViewModel.completePicker(
                requestToken = requestToken,
                selection = result.toDomainSelection(),
            )
        }
    }

    private fun signOut() {
        folderPickerRequestState.retireCurrent()
        retireDriveConnection()
        authViewModel.signOut()
    }

    private fun changeAccount() {
        folderPickerRequestState.retireCurrent()
        retireDriveConnection()
        authViewModel.changeAccount()
    }

    private fun retireDriveConnection() {
        driveAuthorizationCompletionJob?.cancel()
        driveAuthorizationCompletionJob = null
        driveConnectionViewModel.deactivate()
    }

    private suspend fun executeDriveConnectionRequest(request: DriveConnectionRequest) {
        val step = try {
            appContainer.driveConnectionCoordinator.begin(request.account)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            DriveConnectionStep.Complete(DriveConnectionResult.InvalidResponse)
        }
        when (step) {
            is DriveConnectionStep.Complete ->
                driveConnectionViewModel.onResult(request.id, step.result)
            is DriveConnectionStep.ResolutionRequired -> {
                if (request.mode == DriveConnectionRequestMode.SilentProbe) {
                    driveConnectionViewModel.onResult(
                        request.id,
                        DriveConnectionResult.NeedsAuthorization,
                    )
                    return
                }
                if (!driveConnectionViewModel.onAuthorizationResolutionLaunched(request.id)) {
                    return
                }
                try {
                    driveAuthorizationLauncher.launch(
                        IntentSenderRequest.Builder(step.pendingIntent.intentSender).build(),
                    )
                } catch (_: Exception) {
                    driveConnectionViewModel.onAuthorizationLaunchFailed(request.id)
                }
            }
        }
    }

    private fun handleDriveAuthorizationActivityResult(resultCode: Int, data: android.content.Intent?) {
        val pendingRequest = driveConnectionViewModel.pendingAuthorizationRequest() ?: return
        when (val outcome = classifyDriveAuthorizationActivityResult(resultCode, data)) {
            DriveAuthorizationActivityOutcome.Cancelled ->
                driveConnectionViewModel.onAuthorizationCancelled(pendingRequest.id)
            DriveAuthorizationActivityOutcome.LaunchFailed ->
                driveConnectionViewModel.onAuthorizationLaunchFailed(pendingRequest.id)
            DriveAuthorizationActivityOutcome.Invalid ->
                driveConnectionViewModel.onResult(
                    pendingRequest.id,
                    DriveConnectionResult.InvalidResponse,
                )
            is DriveAuthorizationActivityOutcome.Complete -> {
                val request = driveConnectionViewModel.claimAuthorizationResult(pendingRequest.id)
                    ?: return
                driveAuthorizationCompletionJob?.cancel()
                driveAuthorizationCompletionJob = lifecycleScope.launch {
                    val result = try {
                        appContainer.driveConnectionCoordinator.complete(
                            expectedAccount = request.account,
                            data = outcome.data,
                        )
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Exception) {
                        DriveConnectionResult.InvalidResponse
                    }
                    driveConnectionViewModel.onResult(request.id, result)
                }
            }
        }
    }

    @VisibleForTesting
    internal fun stageFolderPickerRequestTokenForTesting(requestToken: String): Boolean =
        folderPickerRequestState.stageForLaunch(requestToken) != null

    @VisibleForTesting
    internal fun launchFolderPickerThroughRegistryForTesting(requestToken: String): String? {
        if (folderPickerRequestState.currentRegistryKey != null) return null
        launchFolderPicker(
            FolderPickerLaunch(
                requestToken = requestToken,
                initialTreeUri = null,
            ),
        )
        return folderPickerRequestState.currentRegistryKey
    }

    @VisibleForTesting
    internal fun deliverFolderPickerResultForTesting(
        registryKey: String,
        result: FolderPickerResult,
    ) {
        handleFolderPickerResult(registryKey, result)
    }

    @VisibleForTesting
    internal val currentFolderPickerRequestTokenForTesting: String?
        get() = folderPickerRequestState.currentToken

    @VisibleForTesting
    internal val currentFolderPickerRegistryKeyForTesting: String?
        get() = folderPickerRequestState.currentRegistryKey

    private fun applyProtectedWindowPolicy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setRecentsScreenshotEnabled(false)
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        protectedWindowPolicyAppliedForTesting = true
    }

    private fun FolderPickerResult.toDomainSelection(): FolderPickerSelection = when (this) {
        FolderPickerResult.Cancelled -> FolderPickerSelection.Cancelled
        is FolderPickerResult.Selected -> FolderPickerSelection.Selected(
            treeUri = treeUri.toString(),
            grantedFlags = grantedFlags,
        )
    }

    internal companion object {
        @VisibleForTesting
        internal var folderPickerActivityResultRegistryFactoryForTesting:
            (() -> ActivityResultRegistry)? = null

        private const val FOLDER_PICKER_REGISTRY_STATE_KEY =
            "folder_picker_activity_result_registry_state"
    }
}
