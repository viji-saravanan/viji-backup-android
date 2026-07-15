package com.aryasubramani.vijibackup.folderaccess.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aryasubramani.vijibackup.folderaccess.domain.BeginFolderPickerResult
import com.aryasubramani.vijibackup.folderaccess.domain.BeginFolderScanResult
import com.aryasubramani.vijibackup.folderaccess.domain.FolderAccessHealth
import com.aryasubramani.vijibackup.folderaccess.domain.FolderMapping
import com.aryasubramani.vijibackup.folderaccess.domain.FolderMappingRepository
import com.aryasubramani.vijibackup.folderaccess.domain.FolderPickerCompletion
import com.aryasubramani.vijibackup.folderaccess.domain.FolderPickerLaunch
import com.aryasubramani.vijibackup.folderaccess.domain.FolderPickerSelection
import com.aryasubramani.vijibackup.folderaccess.domain.FolderScanEvent
import com.aryasubramani.vijibackup.folderaccess.domain.FolderScanProgress
import com.aryasubramani.vijibackup.folderaccess.domain.FolderScanSummary
import com.aryasubramani.vijibackup.folderaccess.domain.RemoveFolderResult
import com.aryasubramani.vijibackup.folderaccess.domain.SetFolderEnabledResult
import com.aryasubramani.vijibackup.folderaccess.domain.ValidateFolderAccessResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FolderAccessUiState(
    val mappings: List<FolderMapping> = emptyList(),
    val isLoading: Boolean = true,
    val removingMappingId: String? = null,
    val updatingEnabledMappingIds: Set<String> = emptySet(),
    val healthByMappingId: Map<String, FolderAccessHealth> = emptyMap(),
    val scanStateByMappingId: Map<String, FolderScanUiState> = emptyMap(),
    val notice: FolderAccessNotice? = null,
)

sealed interface FolderScanUiState {
    data object NotStarted : FolderScanUiState
    data class Running(val progress: FolderScanProgress) : FolderScanUiState
    data class Complete(val summary: FolderScanSummary) : FolderScanUiState
    data class Partial(val summary: FolderScanSummary) : FolderScanUiState
    data class Failed(val summary: FolderScanSummary? = null) : FolderScanUiState
    data class Cancelled(val progress: FolderScanProgress) : FolderScanUiState
}

enum class FolderAccessNotice {
    PickerBusy,
    MappingMissing,
    FolderAdded,
    FolderRepaired,
    FolderRemoved,
    SelectionExpired,
    InvalidSelection,
    ReadPermissionMissing,
    DuplicateFolder,
    GrantFailure,
    RemovalGrantFailure,
    StorageFailure,
    CleanupIncomplete,
}

class FolderAccessViewModel(
    private val repository: FolderMappingRepository,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(FolderAccessUiState())
    private val pickerLaunchChannel = Channel<FolderPickerLaunch>(Channel.BUFFERED)
    private var beginOperation: Job? = null
    private var removeOperation: Job? = null
    private var removeOperationGeneration = 0L
    private val enabledOperations = mutableMapOf<String, Job>()
    private val enabledOperationGenerations = mutableMapOf<String, Long>()
    private val healthOperations = mutableMapOf<String, Job>()
    private val healthOperationGenerations = mutableMapOf<String, Long>()
    private val scanOperations = mutableMapOf<String, Job>()
    private val scanOperationGenerations = mutableMapOf<String, Long>()
    private var mappingObservation: Job? = null
    private var isActive = false

    val uiState: StateFlow<FolderAccessUiState> = mutableUiState.asStateFlow()
    val pickerLaunches: Flow<FolderPickerLaunch> = pickerLaunchChannel.receiveAsFlow()

    fun activate() {
        isActive = true
        if (mappingObservation?.isActive == true) return
        mutableUiState.update { state -> state.copy(isLoading = true) }
        mappingObservation = observeMappings()
    }

    fun deactivate() {
        isActive = false
        beginOperation?.cancel()
        beginOperation = null
        removeOperationGeneration += 1
        removeOperation?.cancel()
        removeOperation = null
        enabledOperations.keys.forEach { mappingId ->
            enabledOperationGenerations[mappingId] =
                enabledOperationGenerations.getValue(mappingId) + 1
        }
        enabledOperations.values.forEach(Job::cancel)
        enabledOperations.clear()
        healthOperations.keys.toList().forEach(::invalidateHealthOperation)
        scanOperations.keys.toList().forEach { mappingId ->
            invalidateScanOperation(mappingId, markCancelled = false)
        }
        mutableUiState.update { state ->
            state.copy(
                mappings = emptyList(),
                isLoading = true,
                removingMappingId = null,
                updatingEnabledMappingIds = emptySet(),
                healthByMappingId = emptyMap(),
                scanStateByMappingId = emptyMap(),
                notice = null,
            )
        }
        mappingObservation?.cancel()
        mappingObservation = null
    }

    fun addFolder() {
        beginPicker { repository.beginAdd() }
    }

    fun repairFolder(mappingId: String) {
        beginPicker(
            beforeStart = { invalidateScanOperation(mappingId, markCancelled = true) },
        ) {
            repository.beginRepair(mappingId)
        }
    }

    fun removeFolder(mappingId: String) {
        if (
            !isActive ||
            beginOperation?.isActive == true ||
            removeOperation?.isActive == true
        ) {
            return
        }
        invalidateHealthOperation(mappingId)
        invalidateScanOperation(mappingId, markCancelled = true)
        val operationGeneration = ++removeOperationGeneration
        removeOperation = viewModelScope.launch {
            mutableUiState.update { state ->
                state.copy(
                    removingMappingId = mappingId,
                    notice = null,
                )
            }
            try {
                val result = try {
                    repository.remove(mappingId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    RemoveFolderResult.StorageFailure
                }
                if (operationGeneration == removeOperationGeneration) {
                    mutableUiState.update { state -> state.copy(notice = result.notice()) }
                    if (result != RemoveFolderResult.Removed) {
                        refreshFolderHealth(mappingId)
                    }
                }
            } finally {
                if (operationGeneration == removeOperationGeneration) {
                    mutableUiState.update { state -> state.copy(removingMappingId = null) }
                    removeOperation = null
                }
            }
        }
    }

    fun setFolderEnabled(mappingId: String, enabled: Boolean) {
        if (!isActive) return
        if (!enabled) {
            invalidateScanOperation(mappingId, markCancelled = true)
        }

        val operationGeneration = enabledOperationGenerations
            .getOrElse(mappingId) { 0L } + 1
        enabledOperationGenerations[mappingId] = operationGeneration
        enabledOperations.remove(mappingId)?.cancel()

        val operation = viewModelScope.launch(start = CoroutineStart.LAZY) {
            mutableUiState.update { state ->
                state.copy(
                    updatingEnabledMappingIds = state.updatingEnabledMappingIds + mappingId,
                    notice = null,
                )
            }
            try {
                val result = try {
                    repository.setEnabled(mappingId, enabled)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    SetFolderEnabledResult.StorageFailure
                }
                if (enabledOperationGenerations[mappingId] == operationGeneration) {
                    mutableUiState.update { state -> state.copy(notice = result.notice()) }
                }
            } finally {
                if (enabledOperationGenerations[mappingId] == operationGeneration) {
                    mutableUiState.update { state ->
                        state.copy(
                            updatingEnabledMappingIds =
                                state.updatingEnabledMappingIds - mappingId,
                        )
                    }
                    enabledOperations.remove(mappingId)
                }
            }
        }
        enabledOperations[mappingId] = operation
        operation.start()
    }

    suspend fun completePicker(
        requestToken: String,
        selection: FolderPickerSelection,
    ): FolderPickerCompletion {
        val completion = try {
            repository.completePicker(requestToken, selection)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            FolderPickerCompletion.StorageFailure
        }
        mutableUiState.update { state ->
            state.copy(notice = completion.notice())
        }
        if (completion is FolderPickerCompletion.Repaired) {
            invalidateHealthOperation(completion.mappingId)
            invalidateScanOperation(completion.mappingId, markCancelled = false)
            refreshFolderHealth(completion.mappingId)
        }
        return completion
    }

    fun refreshFolderHealth() {
        if (!isActive) return
        mutableUiState.value.mappings.forEach { mapping ->
            refreshFolderHealth(mapping.id)
        }
    }

    fun scanFolder(mappingId: String) {
        if (!isActive || scanOperations[mappingId]?.isActive == true) return
        val mappingExists = mutableUiState.value.mappings.any { it.id == mappingId }
        if (!mappingExists) return

        invalidateHealthOperation(mappingId)
        val operationGeneration = nextScanGeneration(mappingId)
        mutableUiState.update { state ->
            state.copy(
                scanStateByMappingId = state.scanStateByMappingId +
                    (mappingId to FolderScanUiState.Running(FolderScanProgress())),
                notice = null,
            )
        }
        val operation = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                when (val result = repository.beginScan(mappingId)) {
                    is BeginFolderScanResult.Ready -> {
                        updateHealthIfCurrent(
                            mappingId,
                            operationGeneration,
                            FolderAccessHealth.Ready,
                        )
                        collectScanEvents(mappingId, operationGeneration, result.events)
                    }
                    is BeginFolderScanResult.AccessUnavailable -> {
                        updateScanAdmissionIfCurrent(
                            mappingId = mappingId,
                            generation = operationGeneration,
                            health = result.health,
                            notice = null,
                        )
                    }
                    BeginFolderScanResult.MappingNotFound -> {
                        updateScanAdmissionIfCurrent(
                            mappingId = mappingId,
                            generation = operationGeneration,
                            health = null,
                            notice = FolderAccessNotice.MappingMissing,
                        )
                    }
                    BeginFolderScanResult.StorageFailure -> {
                        updateScanFailureIfCurrent(mappingId, operationGeneration)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                updateScanFailureIfCurrent(mappingId, operationGeneration)
            } finally {
                if (scanOperationGenerations[mappingId] == operationGeneration) {
                    scanOperations.remove(mappingId)
                }
            }
        }
        scanOperations[mappingId] = operation
        operation.start()
    }

    fun cancelScan(mappingId: String) {
        if (!isActive) return
        invalidateScanOperation(mappingId, markCancelled = true)
    }

    fun clearNotice() {
        mutableUiState.update { state -> state.copy(notice = null) }
    }

    private fun beginPicker(
        beforeStart: () -> Unit = {},
        operation: suspend () -> BeginFolderPickerResult,
    ) {
        if (
            !isActive ||
            beginOperation?.isActive == true ||
            removeOperation?.isActive == true
        ) {
            return
        }
        beforeStart()
        beginOperation = viewModelScope.launch {
            val result = try {
                operation()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                BeginFolderPickerResult.StorageFailure
            }

            when (result) {
                is BeginFolderPickerResult.Started -> {
                    mutableUiState.update { state -> state.copy(notice = null) }
                    pickerLaunchChannel.send(result.request)
                }
                BeginFolderPickerResult.Busy -> showNotice(FolderAccessNotice.PickerBusy)
                BeginFolderPickerResult.MappingNotFound ->
                    showNotice(FolderAccessNotice.MappingMissing)
                BeginFolderPickerResult.StorageFailure ->
                    showNotice(FolderAccessNotice.StorageFailure)
            }
        }
    }

    private fun observeMappings(): Job = viewModelScope.launch {
        try {
            repository.observeMappings().collect { mappings ->
                val currentMappingIds = mutableUiState.value.mappings.mapTo(mutableSetOf()) {
                    it.id
                }
                val nextMappingIds = mappings.mapTo(mutableSetOf()) { it.id }
                val removedMappingIds = currentMappingIds - nextMappingIds
                val addedMappingIds = nextMappingIds - currentMappingIds
                removedMappingIds.forEach { mappingId ->
                    invalidateHealthOperation(mappingId)
                    invalidateScanOperation(mappingId, markCancelled = false)
                }
                mutableUiState.update { state ->
                    state.copy(
                        mappings = mappings,
                        isLoading = false,
                        healthByMappingId = state.healthByMappingId
                            .filterKeys(nextMappingIds::contains)
                            .toMutableMap()
                            .apply {
                                addedMappingIds.forEach { mappingId ->
                                    put(mappingId, FolderAccessHealth.Checking)
                                }
                            },
                        scanStateByMappingId = state.scanStateByMappingId
                            .filterKeys(nextMappingIds::contains)
                            .toMutableMap()
                            .apply {
                                addedMappingIds.forEach { mappingId ->
                                    put(mappingId, FolderScanUiState.NotStarted)
                                }
                            },
                    )
                }
                addedMappingIds.forEach(::refreshFolderHealth)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutableUiState.update { state ->
                state.copy(
                    isLoading = false,
                    notice = FolderAccessNotice.StorageFailure,
                )
            }
        }
    }

    private fun refreshFolderHealth(mappingId: String) {
        if (!isActive || mutableUiState.value.mappings.none { it.id == mappingId }) return

        val operationGeneration = nextHealthGeneration(mappingId)
        healthOperations.remove(mappingId)?.cancel()
        mutableUiState.update { state ->
            state.copy(
                healthByMappingId = state.healthByMappingId +
                    (mappingId to FolderAccessHealth.Checking),
            )
        }
        val operation = viewModelScope.launch(start = CoroutineStart.LAZY) {
            val health = try {
                when (val result = repository.validate(mappingId)) {
                    is ValidateFolderAccessResult.Found -> result.health
                    ValidateFolderAccessResult.MappingNotFound,
                    ValidateFolderAccessResult.StorageFailure ->
                        FolderAccessHealth.TemporarilyUnavailable
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                FolderAccessHealth.TemporarilyUnavailable
            }
            if (healthOperationGenerations[mappingId] == operationGeneration &&
                mutableUiState.value.mappings.any { it.id == mappingId }
            ) {
                mutableUiState.update { state ->
                    state.copy(
                        healthByMappingId = state.healthByMappingId + (mappingId to health),
                    )
                }
            }
            if (healthOperationGenerations[mappingId] == operationGeneration) {
                healthOperations.remove(mappingId)
            }
        }
        healthOperations[mappingId] = operation
        operation.start()
    }

    private suspend fun collectScanEvents(
        mappingId: String,
        generation: Long,
        events: Flow<FolderScanEvent>,
    ) {
        var terminalReceived = false
        events.collect { event ->
            if (terminalReceived || scanOperationGenerations[mappingId] != generation) {
                return@collect
            }
            when (event) {
                is FolderScanEvent.Progress -> updateScanProgress(mappingId, event.value)
                is FolderScanEvent.Complete -> {
                    terminalReceived = true
                    updateTerminalScanState(mappingId) { progress ->
                        FolderScanUiState.Complete(event.summary.withProgress(progress))
                    }
                }
                is FolderScanEvent.Partial -> {
                    terminalReceived = true
                    updateTerminalScanState(mappingId) { progress ->
                        FolderScanUiState.Partial(event.summary.withProgress(progress))
                    }
                }
                is FolderScanEvent.Failed -> {
                    terminalReceived = true
                    updateTerminalScanState(mappingId) { progress ->
                        FolderScanUiState.Failed(event.summary.withProgress(progress))
                    }
                }
            }
        }
        if (!terminalReceived && scanOperationGenerations[mappingId] == generation) {
            updateScanFailureIfCurrent(mappingId, generation)
        }
    }

    private fun updateScanProgress(mappingId: String, progress: FolderScanProgress) {
        mutableUiState.update { state ->
            val current = state.scanStateByMappingId[mappingId] as? FolderScanUiState.Running
                ?: return@update state
            state.copy(
                scanStateByMappingId = state.scanStateByMappingId +
                    (mappingId to FolderScanUiState.Running(current.progress.merge(progress))),
            )
        }
    }

    private fun updateTerminalScanState(
        mappingId: String,
        terminalState: (FolderScanProgress) -> FolderScanUiState,
    ) {
        mutableUiState.update { state ->
            val progress = state.scanStateByMappingId[mappingId].progressOrEmpty()
            state.copy(
                scanStateByMappingId = state.scanStateByMappingId +
                    (mappingId to terminalState(progress)),
            )
        }
    }

    private fun updateScanAdmissionIfCurrent(
        mappingId: String,
        generation: Long,
        health: FolderAccessHealth?,
        notice: FolderAccessNotice?,
    ) {
        if (scanOperationGenerations[mappingId] != generation) return
        mutableUiState.update { state ->
            state.copy(
                healthByMappingId = if (health == null) {
                    state.healthByMappingId
                } else {
                    state.healthByMappingId + (mappingId to health)
                },
                scanStateByMappingId = state.scanStateByMappingId +
                    (mappingId to FolderScanUiState.NotStarted),
                notice = notice,
            )
        }
    }

    private fun updateScanFailureIfCurrent(mappingId: String, generation: Long) {
        if (scanOperationGenerations[mappingId] != generation) return
        mutableUiState.update { state ->
            state.copy(
                scanStateByMappingId = state.scanStateByMappingId +
                    (mappingId to FolderScanUiState.Failed()),
                notice = FolderAccessNotice.StorageFailure,
            )
        }
    }

    private fun updateHealthIfCurrent(
        mappingId: String,
        generation: Long,
        health: FolderAccessHealth,
    ) {
        if (scanOperationGenerations[mappingId] != generation) return
        mutableUiState.update { state ->
            state.copy(healthByMappingId = state.healthByMappingId + (mappingId to health))
        }
    }

    private fun invalidateHealthOperation(mappingId: String) {
        nextHealthGeneration(mappingId)
        healthOperations.remove(mappingId)?.cancel()
    }

    private fun invalidateScanOperation(mappingId: String, markCancelled: Boolean) {
        val operation = scanOperations.remove(mappingId) ?: return
        nextScanGeneration(mappingId)
        if (markCancelled) {
            mutableUiState.update { state ->
                val current = state.scanStateByMappingId[mappingId]
                if (current !is FolderScanUiState.Running) return@update state
                state.copy(
                    scanStateByMappingId = state.scanStateByMappingId +
                        (mappingId to FolderScanUiState.Cancelled(current.progress)),
                )
            }
        }
        operation.cancel()
    }

    private fun nextHealthGeneration(mappingId: String): Long =
        (healthOperationGenerations[mappingId] ?: 0L).inc().also { generation ->
            healthOperationGenerations[mappingId] = generation
        }

    private fun nextScanGeneration(mappingId: String): Long =
        (scanOperationGenerations[mappingId] ?: 0L).inc().also { generation ->
            scanOperationGenerations[mappingId] = generation
        }

    private fun showNotice(notice: FolderAccessNotice) {
        mutableUiState.update { state -> state.copy(notice = notice) }
    }

    private fun FolderPickerCompletion.notice(): FolderAccessNotice? = when (this) {
        is FolderPickerCompletion.Added -> FolderAccessNotice.FolderAdded
        is FolderPickerCompletion.Repaired -> FolderAccessNotice.FolderRepaired
        FolderPickerCompletion.Cancelled -> null
        FolderPickerCompletion.Stale -> FolderAccessNotice.SelectionExpired
        FolderPickerCompletion.InvalidSelection -> FolderAccessNotice.InvalidSelection
        FolderPickerCompletion.ReadPermissionMissing -> FolderAccessNotice.ReadPermissionMissing
        FolderPickerCompletion.Duplicate -> FolderAccessNotice.DuplicateFolder
        FolderPickerCompletion.GrantFailure -> FolderAccessNotice.GrantFailure
        FolderPickerCompletion.StorageFailure -> FolderAccessNotice.StorageFailure
        FolderPickerCompletion.CleanupIncomplete -> FolderAccessNotice.CleanupIncomplete
    }

    private fun RemoveFolderResult.notice(): FolderAccessNotice = when (this) {
        RemoveFolderResult.Removed -> FolderAccessNotice.FolderRemoved
        RemoveFolderResult.MappingNotFound -> FolderAccessNotice.MappingMissing
        RemoveFolderResult.Busy -> FolderAccessNotice.PickerBusy
        RemoveFolderResult.GrantFailure -> FolderAccessNotice.RemovalGrantFailure
        RemoveFolderResult.StorageFailure -> FolderAccessNotice.StorageFailure
    }

    private fun SetFolderEnabledResult.notice(): FolderAccessNotice? = when (this) {
        SetFolderEnabledResult.Updated -> null
        SetFolderEnabledResult.MappingNotFound -> FolderAccessNotice.MappingMissing
        SetFolderEnabledResult.StorageFailure -> FolderAccessNotice.StorageFailure
    }

    class Factory(
        private val repository: FolderMappingRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(FolderAccessViewModel::class.java)) {
                "Unsupported ViewModel class: ${modelClass.name}"
            }
            return FolderAccessViewModel(repository) as T
        }
    }
}

private fun FolderScanProgress.merge(other: FolderScanProgress) = FolderScanProgress(
    foldersVisited = maxOf(foldersVisited, other.foldersVisited),
    filesDiscovered = maxOf(filesDiscovered, other.filesDiscovered),
    knownBytes = maxOf(knownBytes, other.knownBytes),
    filesWithUnknownSize = maxOf(filesWithUnknownSize, other.filesWithUnknownSize),
    unreadableEntries = maxOf(unreadableEntries, other.unreadableEntries),
)

private fun FolderScanSummary.withProgress(progress: FolderScanProgress) = copy(
    progress = progress.merge(this.progress),
)

private fun FolderScanUiState?.progressOrEmpty(): FolderScanProgress = when (this) {
    is FolderScanUiState.Running -> progress
    is FolderScanUiState.Complete -> summary.progress
    is FolderScanUiState.Partial -> summary.progress
    is FolderScanUiState.Failed -> summary?.progress ?: FolderScanProgress()
    is FolderScanUiState.Cancelled -> progress
    FolderScanUiState.NotStarted,
    null -> FolderScanProgress()
}
