package com.aryasubramani.vijibackup.downloadsaccess.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aryasubramani.vijibackup.downloadsaccess.domain.DownloadsAccessHealth
import com.aryasubramani.vijibackup.downloadsaccess.domain.DownloadsAccessManager
import com.aryasubramani.vijibackup.downloadsaccess.domain.DownloadsAccessResult
import com.aryasubramani.vijibackup.downloadsaccess.domain.DownloadsAccessSnapshot
import com.aryasubramani.vijibackup.downloadsaccess.domain.DownloadsScanEvent
import com.aryasubramani.vijibackup.downloadsaccess.domain.DownloadsScanProgress
import com.aryasubramani.vijibackup.downloadsaccess.domain.DownloadsScanSummary
import com.aryasubramani.vijibackup.downloadsaccess.domain.DownloadsScanner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DownloadsAccessUiState(
    val snapshot: DownloadsAccessSnapshot? = null,
    val isLoading: Boolean = true,
    val isBusy: Boolean = false,
    val isAwaitingSettings: Boolean = false,
    val scanState: DownloadsScanUiState = DownloadsScanUiState.Idle,
    val notice: DownloadsAccessNotice? = null,
)

sealed interface DownloadsScanUiState {
    data object Idle : DownloadsScanUiState
    data class Running(val progress: DownloadsScanProgress) : DownloadsScanUiState
    data class Cancelling(val progress: DownloadsScanProgress) : DownloadsScanUiState
    data class Complete(val summary: DownloadsScanSummary) : DownloadsScanUiState
    data class Partial(val summary: DownloadsScanSummary) : DownloadsScanUiState
    data class Failed(val summary: DownloadsScanSummary? = null) : DownloadsScanUiState
    data class Cancelled(val progress: DownloadsScanProgress) : DownloadsScanUiState
}

enum class DownloadsAccessNotice {
    PermissionNotGranted,
    SettingsUnavailable,
    SourceRemoved,
    StorageFailure,
}

enum class DownloadsSettingsPurpose {
    ConfigureOrRepair,
    ReviewPermission,
}

data class DownloadsSettingsLaunch(
    val id: Long,
    val purpose: DownloadsSettingsPurpose,
)

class DownloadsAccessViewModel(
    private val manager: DownloadsAccessManager,
    private val scanner: DownloadsScanner,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(DownloadsAccessUiState())
    private val settingsLaunchChannel = Channel<DownloadsSettingsLaunch>(Channel.BUFFERED)
    private var operation: Job? = null
    private var operationGeneration = 0L
    private var scanOperation: Job? = null
    private var scanGeneration = 0L
    private var cancellationRequestedGeneration: Long? = null
    private var nextSettingsRequestId = 0L
    private var currentSettingsRequest: DownloadsSettingsLaunch? = null
    private var isActive = false

    val uiState: StateFlow<DownloadsAccessUiState> = mutableUiState.asStateFlow()
    val settingsLaunches: Flow<DownloadsSettingsLaunch> = settingsLaunchChannel.receiveAsFlow()

    fun activate() {
        if (isActive) return
        isActive = true
        refresh()
    }

    fun deactivate() {
        isActive = false
        operationGeneration += 1
        operation?.cancel()
        operation = null
        invalidateScan(DownloadsScanUiState.Idle)
        currentSettingsRequest = null
        mutableUiState.value = DownloadsAccessUiState()
    }

    fun refresh() {
        if (currentSettingsRequest != null) return
        runOperation(isMutation = false, operation = manager::refresh)
    }

    fun requestAccess() {
        if (!isActive || mutableUiState.value.isBusy || currentSettingsRequest != null) return
        when (mutableUiState.value.snapshot?.health) {
            DownloadsAccessHealth.NotConfigured,
            DownloadsAccessHealth.NeedsPermission,
            -> requestSettings(DownloadsSettingsPurpose.ConfigureOrRepair)
            DownloadsAccessHealth.PermissionGrantedButUnused -> runOperation(
                isMutation = true,
                operation = manager::configureFromCurrentPermission,
            )
            else -> Unit
        }
    }

    fun reviewPermission() {
        if (!isActive || mutableUiState.value.isBusy || currentSettingsRequest != null) return
        if (mutableUiState.value.snapshot?.health == DownloadsAccessHealth.UseSafPicker) return
        stopScanForSourceChange(resetState = false)
        requestSettings(DownloadsSettingsPurpose.ReviewPermission)
    }

    fun onSettingsResult() {
        val request = currentSettingsRequest ?: return
        currentSettingsRequest = null
        mutableUiState.update { state -> state.copy(isAwaitingSettings = false) }
        if (!isActive) return
        when (request.purpose) {
            DownloadsSettingsPurpose.ConfigureOrRepair -> runOperation(
                isMutation = true,
                resultNotice = { result ->
                    val health = (result as? DownloadsAccessResult.Success)?.snapshot?.health
                    if (
                        health == DownloadsAccessHealth.NotConfigured ||
                        health == DownloadsAccessHealth.NeedsPermission
                    ) {
                        DownloadsAccessNotice.PermissionNotGranted
                    } else {
                        null
                    }
                },
                operation = manager::configureFromCurrentPermission,
            )
            DownloadsSettingsPurpose.ReviewPermission -> refresh()
        }
    }

    fun onSettingsLaunchFailed(requestId: Long) {
        if (currentSettingsRequest?.id != requestId) return
        currentSettingsRequest = null
        mutableUiState.update { state ->
            state.copy(
                isAwaitingSettings = false,
                notice = if (isActive) DownloadsAccessNotice.SettingsUnavailable else null,
            )
        }
    }

    fun setEnabled(enabled: Boolean) {
        stopScanForSourceChange(resetState = false)
        runOperation(isMutation = true) { manager.setEnabled(enabled) }
    }

    fun remove() {
        stopScanForSourceChange(resetState = true)
        runOperation(
            isMutation = true,
            resultNotice = { result ->
                if (result is DownloadsAccessResult.Success) {
                    DownloadsAccessNotice.SourceRemoved
                } else {
                    null
                }
            },
            operation = manager::remove,
        )
    }

    fun scan() {
        if (
            !isActive ||
            mutableUiState.value.snapshot?.health != DownloadsAccessHealth.Ready ||
            operation?.isActive == true ||
            scanOperation?.isActive == true ||
            currentSettingsRequest != null
        ) {
            return
        }
        val generation = ++scanGeneration
        cancellationRequestedGeneration = null
        scanOperation = viewModelScope.launch {
            var lastProgress = DownloadsScanProgress()
            var terminalReceived = false
            val admission = safeAccessResult(manager::refresh)
            if (!isActive || generation != scanGeneration) return@launch
            if (admission !is DownloadsAccessResult.Success) {
                mutableUiState.update { state ->
                    state.copy(
                        scanState = DownloadsScanUiState.Failed(),
                        notice = DownloadsAccessNotice.StorageFailure,
                    )
                }
                return@launch
            }
            mutableUiState.update { state -> state.copy(snapshot = admission.snapshot) }
            if (admission.snapshot.health != DownloadsAccessHealth.Ready) {
                mutableUiState.update { state -> state.copy(scanState = DownloadsScanUiState.Idle) }
                return@launch
            }
            mutableUiState.update { state ->
                state.copy(
                    scanState = DownloadsScanUiState.Running(lastProgress),
                    notice = null,
                )
            }

            try {
                scanner.scan().takeWhile { event ->
                    if (!isActive || generation != scanGeneration) return@takeWhile false
                    if (cancellationRequestedGeneration == generation) return@takeWhile false
                    when (event) {
                        is DownloadsScanEvent.Progress -> {
                            lastProgress = event.value
                            mutableUiState.update { state ->
                                state.copy(scanState = DownloadsScanUiState.Running(event.value))
                            }
                        }
                        is DownloadsScanEvent.Complete -> {
                            terminalReceived = true
                            lastProgress = event.summary.progress
                            mutableUiState.update { state ->
                                state.copy(scanState = DownloadsScanUiState.Complete(event.summary))
                            }
                        }
                        is DownloadsScanEvent.Partial -> {
                            terminalReceived = true
                            lastProgress = event.summary.progress
                            mutableUiState.update { state ->
                                state.copy(scanState = DownloadsScanUiState.Partial(event.summary))
                            }
                        }
                        is DownloadsScanEvent.Failed -> {
                            terminalReceived = true
                            lastProgress = event.summary.progress
                            mutableUiState.update { state ->
                                state.copy(scanState = DownloadsScanUiState.Failed(event.summary))
                            }
                        }
                    }
                    !terminalReceived
                }.collect {}
                if (!isActive || generation != scanGeneration) return@launch
                if (cancellationRequestedGeneration == generation) {
                    mutableUiState.update { state ->
                        state.copy(scanState = DownloadsScanUiState.Cancelled(lastProgress))
                    }
                } else if (!terminalReceived) {
                    mutableUiState.update { state ->
                        state.copy(scanState = DownloadsScanUiState.Failed())
                    }
                }
            } catch (cancelled: CancellationException) {
                if (
                    isActive &&
                    generation == scanGeneration &&
                    cancellationRequestedGeneration == generation
                ) {
                    mutableUiState.update { state ->
                        state.copy(scanState = DownloadsScanUiState.Cancelled(lastProgress))
                    }
                }
                throw cancelled
            } catch (_: Exception) {
                if (isActive && generation == scanGeneration) {
                    mutableUiState.update { state ->
                        state.copy(scanState = DownloadsScanUiState.Failed())
                    }
                }
            }
        }
    }

    fun cancelScan() {
        val generation = scanGeneration
        if (!isActive || scanOperation?.isActive != true) return
        val progress = mutableUiState.value.scanState.progress()
        cancellationRequestedGeneration = generation
        mutableUiState.update { state ->
            state.copy(scanState = DownloadsScanUiState.Cancelling(progress))
        }
        scanOperation?.cancel()
    }

    private fun requestSettings(purpose: DownloadsSettingsPurpose) {
        val request = DownloadsSettingsLaunch(
            id = ++nextSettingsRequestId,
            purpose = purpose,
        )
        currentSettingsRequest = request
        mutableUiState.update { state ->
            state.copy(isAwaitingSettings = true, notice = null)
        }
        if (settingsLaunchChannel.trySend(request).isFailure) {
            onSettingsLaunchFailed(request.id)
        }
    }

    private fun runOperation(
        isMutation: Boolean,
        resultNotice: (DownloadsAccessResult) -> DownloadsAccessNotice? = { null },
        operation: suspend () -> DownloadsAccessResult,
    ) {
        if (!isActive || this.operation?.isActive == true) return
        val generation = ++operationGeneration
        this.operation = viewModelScope.launch {
            mutableUiState.update { state ->
                state.copy(
                    isLoading = !isMutation && state.snapshot == null,
                    isBusy = isMutation,
                    notice = null,
                )
            }
            val result = try {
                safeAccessResult(operation)
            } catch (cancelled: CancellationException) {
                throw cancelled
            }
            if (!isActive || generation != operationGeneration) return@launch
            if (
                result is DownloadsAccessResult.Success &&
                result.snapshot.health != DownloadsAccessHealth.Ready &&
                scanOperation?.isActive == true
            ) {
                stopScanForSourceChange(resetState = false)
            }
            val scanState = mutableUiState.value.scanState
            mutableUiState.value = when (result) {
                is DownloadsAccessResult.Success -> DownloadsAccessUiState(
                    snapshot = result.snapshot,
                    isLoading = false,
                    scanState = scanState,
                    notice = resultNotice(result),
                )
                DownloadsAccessResult.PersistenceFailure -> mutableUiState.value.copy(
                    isLoading = false,
                    isBusy = false,
                    notice = DownloadsAccessNotice.StorageFailure,
                )
            }
        }
    }

    private suspend fun safeAccessResult(
        block: suspend () -> DownloadsAccessResult,
    ): DownloadsAccessResult = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        DownloadsAccessResult.PersistenceFailure
    }

    private fun stopScanForSourceChange(resetState: Boolean) {
        val current = mutableUiState.value.scanState
        val replacement = when {
            resetState -> DownloadsScanUiState.Idle
            scanOperation?.isActive == true -> DownloadsScanUiState.Cancelled(current.progress())
            else -> current
        }
        invalidateScan(replacement)
    }

    private fun invalidateScan(replacement: DownloadsScanUiState) {
        scanGeneration += 1
        cancellationRequestedGeneration = null
        scanOperation?.cancel()
        scanOperation = null
        mutableUiState.update { state -> state.copy(scanState = replacement) }
    }

    private fun DownloadsScanUiState.progress(): DownloadsScanProgress = when (this) {
        DownloadsScanUiState.Idle -> DownloadsScanProgress()
        is DownloadsScanUiState.Running -> progress
        is DownloadsScanUiState.Cancelling -> progress
        is DownloadsScanUiState.Complete -> summary.progress
        is DownloadsScanUiState.Partial -> summary.progress
        is DownloadsScanUiState.Failed -> summary?.progress ?: DownloadsScanProgress()
        is DownloadsScanUiState.Cancelled -> progress
    }

    class Factory(
        private val manager: DownloadsAccessManager,
        private val scanner: DownloadsScanner,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(DownloadsAccessViewModel::class.java))
            return DownloadsAccessViewModel(manager, scanner) as T
        }
    }
}
