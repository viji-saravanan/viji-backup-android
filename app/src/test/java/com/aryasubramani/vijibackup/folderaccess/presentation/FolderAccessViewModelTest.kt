package com.aryasubramani.vijibackup.folderaccess.presentation

import com.aryasubramani.vijibackup.auth.presentation.MainDispatcherRule
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
import com.aryasubramani.vijibackup.folderaccess.domain.PendingFolderCleanupResult
import com.aryasubramani.vijibackup.folderaccess.domain.RemoveFolderResult
import com.aryasubramani.vijibackup.folderaccess.domain.SetFolderEnabledResult
import com.aryasubramani.vijibackup.folderaccess.domain.ValidateFolderAccessResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FolderAccessViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun observedMappingsBecomeReadyWithoutExposingStorageIdentifiers() = runTest {
        val repository = FakeFolderMappingRepository().apply {
            mappings.value = listOf(
                FolderMapping(
                    id = "mapping-a",
                    displayName = null,
                    enabled = true,
                ),
            )
        }
        val viewModel = FolderAccessViewModel(repository)

        assertEquals(0, repository.observeCalls)
        viewModel.activate()
        runCurrent()

        assertEquals(
            FolderAccessUiState(
                mappings = repository.mappings.value,
                isLoading = false,
                healthByMappingId = mapOf("mapping-a" to FolderAccessHealth.Ready),
                scanStateByMappingId = mapOf("mapping-a" to FolderScanUiState.NotStarted),
            ),
            viewModel.uiState.value,
        )
    }

    @Test
    fun activationAndForegroundRefreshEveryMappingIndependently() = runTest {
        val mappings = FolderAccessHealth.entries.mapIndexed { index, _ ->
            FolderMapping("mapping-$index", "Folder $index", enabled = true)
        }
        val repository = FakeFolderMappingRepository().apply {
            this.mappings.value = mappings
            FolderAccessHealth.entries.forEachIndexed { index, health ->
                validateResults["mapping-$index"] = ValidateFolderAccessResult.Found(health)
            }
        }
        val viewModel = FolderAccessViewModel(repository)

        viewModel.activate()
        advanceUntilIdle()

        assertEquals(mappings.map(FolderMapping::id), repository.validateCalls)
        assertEquals(
            FolderAccessHealth.entries,
            mappings.map { viewModel.uiState.value.healthByMappingId.getValue(it.id) },
        )

        repository.validateCalls.clear()
        repository.validateResults.replaceAll { _, _ ->
            ValidateFolderAccessResult.Found(FolderAccessHealth.PermissionMissing)
        }
        viewModel.refreshFolderHealth()
        advanceUntilIdle()

        assertEquals(mappings.map(FolderMapping::id), repository.validateCalls)
        assertTrue(
            viewModel.uiState.value.healthByMappingId.values.all {
                it == FolderAccessHealth.PermissionMissing
            },
        )
    }

    @Test
    fun validationFailureIsIsolatedAndMappingDisappearancePrunesTransientState() = runTest {
        val repository = FakeFolderMappingRepository().apply {
            mappings.value = listOf(
                FolderMapping("healthy", "Healthy", enabled = true),
                FolderMapping("broken", "Broken", enabled = true),
            )
            validateResults["healthy"] =
                ValidateFolderAccessResult.Found(FolderAccessHealth.Ready)
            validateResults["broken"] = ValidateFolderAccessResult.StorageFailure
        }
        val viewModel = FolderAccessViewModel(repository)
        viewModel.activate()
        advanceUntilIdle()

        assertEquals(FolderAccessHealth.Ready, viewModel.uiState.value.healthByMappingId["healthy"])
        assertEquals(
            FolderAccessHealth.TemporarilyUnavailable,
            viewModel.uiState.value.healthByMappingId["broken"],
        )

        repository.mappings.value = listOf(
            FolderMapping("healthy", "Healthy", enabled = true),
        )
        advanceUntilIdle()

        assertTrue("broken" !in viewModel.uiState.value.healthByMappingId)
        assertTrue("broken" !in viewModel.uiState.value.scanStateByMappingId)
    }

    @Test
    fun disabledReadyMappingScansWithMonotonicProgressAndExactTerminalState() = runTest {
        val events = MutableSharedFlow<FolderScanEvent>(extraBufferCapacity = 4)
        val mapping = FolderMapping("mapping-a", "Paused", enabled = false)
        val repository = FakeFolderMappingRepository().apply {
            mappings.value = listOf(mapping)
            beginScanResults[mapping.id] = BeginFolderScanResult.Ready(events)
        }
        val viewModel = FolderAccessViewModel(repository)
        viewModel.activate()
        advanceUntilIdle()

        viewModel.scanFolder(mapping.id)
        runCurrent()
        assertEquals(
            FolderScanUiState.Running(FolderScanProgress()),
            viewModel.uiState.value.scanStateByMappingId[mapping.id],
        )

        events.emit(FolderScanEvent.Progress(FolderScanProgress(filesDiscovered = 5)))
        runCurrent()
        events.emit(FolderScanEvent.Progress(FolderScanProgress(filesDiscovered = 2)))
        runCurrent()
        assertEquals(
            5L,
            (viewModel.uiState.value.scanStateByMappingId[mapping.id] as
                FolderScanUiState.Running).progress.filesDiscovered,
        )

        val summary = FolderScanSummary(
            progress = FolderScanProgress(filesDiscovered = 4, knownBytes = 12),
            elapsedTimeMillis = 25,
            issues = emptySet(),
        )
        events.emit(FolderScanEvent.Complete(summary))
        advanceUntilIdle()

        val complete = viewModel.uiState.value.scanStateByMappingId[mapping.id] as
            FolderScanUiState.Complete
        assertEquals(5L, complete.summary.progress.filesDiscovered)
        assertEquals(12L, complete.summary.progress.knownBytes)
        assertEquals(listOf(mapping.id), repository.beginScanCalls)
    }

    @Test
    fun scanAdmissionOutcomesUpdateOnlyTheTargetMapping() = runTest {
        val mappings = listOf(
            FolderMapping("degraded", "Degraded", enabled = true),
            FolderMapping("missing", "Missing", enabled = true),
            FolderMapping("storage", "Storage", enabled = true),
        )
        val repository = FakeFolderMappingRepository().apply {
            this.mappings.value = mappings
            beginScanResults["degraded"] = BeginFolderScanResult.AccessUnavailable(
                FolderAccessHealth.ProviderAuthRequired,
            )
            beginScanResults["missing"] = BeginFolderScanResult.MappingNotFound
            beginScanResults["storage"] = BeginFolderScanResult.StorageFailure
        }
        val viewModel = FolderAccessViewModel(repository)
        viewModel.activate()
        advanceUntilIdle()

        viewModel.scanFolder("degraded")
        advanceUntilIdle()
        assertEquals(
            FolderAccessHealth.ProviderAuthRequired,
            viewModel.uiState.value.healthByMappingId["degraded"],
        )
        assertEquals(FolderScanUiState.NotStarted, viewModel.uiState.value.scanStateByMappingId["degraded"])

        viewModel.scanFolder("missing")
        advanceUntilIdle()
        assertEquals(FolderAccessNotice.MappingMissing, viewModel.uiState.value.notice)

        viewModel.scanFolder("storage")
        advanceUntilIdle()
        assertEquals(FolderAccessNotice.StorageFailure, viewModel.uiState.value.notice)
        assertEquals(listOf("degraded", "missing", "storage"), repository.beginScanCalls)
    }

    @Test
    fun lateHealthRefreshCannotOverwriteFresherScanAdmission() = runTest {
        val validationStarted = CompletableDeferred<Unit>()
        val finishValidation = CompletableDeferred<Unit>()
        val mapping = FolderMapping("mapping-a", "A", enabled = true)
        val repository = FakeFolderMappingRepository().apply {
            mappings.value = listOf(mapping)
            validateHandler = {
                validationStarted.complete(Unit)
                withContext(NonCancellable) { finishValidation.await() }
                ValidateFolderAccessResult.Found(FolderAccessHealth.Ready)
            }
            beginScanResults[mapping.id] = BeginFolderScanResult.AccessUnavailable(
                FolderAccessHealth.PermissionMissing,
            )
        }
        val viewModel = FolderAccessViewModel(repository)
        viewModel.activate()
        runCurrent()
        assertTrue(validationStarted.isCompleted)

        viewModel.scanFolder(mapping.id)
        runCurrent()
        assertEquals(
            FolderAccessHealth.PermissionMissing,
            viewModel.uiState.value.healthByMappingId[mapping.id],
        )

        finishValidation.complete(Unit)
        advanceUntilIdle()
        assertEquals(
            FolderAccessHealth.PermissionMissing,
            viewModel.uiState.value.healthByMappingId[mapping.id],
        )
    }

    @Test
    fun repairAndRemoveCancelOnlyTheirTargetScans() = runTest {
        val mappings = listOf(
            FolderMapping("repair", "Repair", enabled = true),
            FolderMapping("remove", "Remove", enabled = true),
            FolderMapping("untouched", "Untouched", enabled = true),
        )
        val repository = FakeFolderMappingRepository().apply {
            this.mappings.value = mappings
            mappings.forEach { mapping ->
                beginScanResults[mapping.id] = BeginFolderScanResult.Ready(MutableSharedFlow())
            }
            beginRepairResult = BeginFolderPickerResult.Busy
            removeResult = RemoveFolderResult.StorageFailure
        }
        val viewModel = FolderAccessViewModel(repository)
        viewModel.activate()
        advanceUntilIdle()
        mappings.forEach { viewModel.scanFolder(it.id) }
        runCurrent()

        viewModel.repairFolder("repair")
        runCurrent()
        viewModel.removeFolder("remove")
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.scanStateByMappingId["repair"] is FolderScanUiState.Cancelled)
        assertTrue(viewModel.uiState.value.scanStateByMappingId["remove"] is FolderScanUiState.Cancelled)
        assertTrue(viewModel.uiState.value.scanStateByMappingId["untouched"] is FolderScanUiState.Running)
    }

    @Test
    fun concurrentScansAndCancellationStayIsolatedByMapping() = runTest {
        val firstEvents = MutableSharedFlow<FolderScanEvent>(extraBufferCapacity = 2)
        val secondEvents = MutableSharedFlow<FolderScanEvent>(extraBufferCapacity = 2)
        val mappings = listOf(
            FolderMapping("mapping-a", "A", enabled = true),
            FolderMapping("mapping-b", "B", enabled = true),
        )
        val repository = FakeFolderMappingRepository().apply {
            this.mappings.value = mappings
            beginScanResults["mapping-a"] = BeginFolderScanResult.Ready(firstEvents)
            beginScanResults["mapping-b"] = BeginFolderScanResult.Ready(secondEvents)
        }
        val viewModel = FolderAccessViewModel(repository)
        viewModel.activate()
        advanceUntilIdle()

        viewModel.scanFolder("mapping-a")
        viewModel.scanFolder("mapping-b")
        runCurrent()
        firstEvents.emit(FolderScanEvent.Progress(FolderScanProgress(filesDiscovered = 1)))
        secondEvents.emit(FolderScanEvent.Progress(FolderScanProgress(filesDiscovered = 8)))
        runCurrent()

        viewModel.cancelScan("mapping-a")
        runCurrent()

        assertEquals(
            FolderScanUiState.Cancelled(FolderScanProgress(filesDiscovered = 1)),
            viewModel.uiState.value.scanStateByMappingId["mapping-a"],
        )
        assertEquals(
            FolderScanUiState.Running(FolderScanProgress(filesDiscovered = 8)),
            viewModel.uiState.value.scanStateByMappingId["mapping-b"],
        )

        val secondSummary = FolderScanSummary(
            progress = FolderScanProgress(filesDiscovered = 8),
            elapsedTimeMillis = 10,
            issues = emptySet(),
        )
        secondEvents.emit(FolderScanEvent.Complete(secondSummary))
        advanceUntilIdle()
        assertTrue(
            viewModel.uiState.value.scanStateByMappingId["mapping-b"] is FolderScanUiState.Complete,
        )
    }

    @Test
    fun disableAndDeactivationCancelRunningScansWithoutLateState() = runTest {
        val events = MutableSharedFlow<FolderScanEvent>()
        val mapping = FolderMapping("mapping-a", "A", enabled = true)
        val repository = FakeFolderMappingRepository().apply {
            mappings.value = listOf(mapping)
            beginScanResults[mapping.id] = BeginFolderScanResult.Ready(events)
            setEnabledResult = SetFolderEnabledResult.Updated
        }
        val viewModel = FolderAccessViewModel(repository)
        viewModel.activate()
        advanceUntilIdle()

        viewModel.scanFolder(mapping.id)
        runCurrent()
        viewModel.setFolderEnabled(mapping.id, false)
        advanceUntilIdle()
        assertTrue(
            viewModel.uiState.value.scanStateByMappingId[mapping.id] is FolderScanUiState.Cancelled,
        )

        viewModel.scanFolder(mapping.id)
        runCurrent()
        viewModel.deactivate()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.mappings.isEmpty())
        assertTrue(viewModel.uiState.value.healthByMappingId.isEmpty())
        assertTrue(viewModel.uiState.value.scanStateByMappingId.isEmpty())
    }

    @Test
    fun mappingObservationFailureBecomesNonSensitiveStorageNotice() = runTest {
        val repository = FakeFolderMappingRepository().apply {
            observationFailure = IllegalStateException("sensitive provider detail")
        }
        val viewModel = FolderAccessViewModel(repository)

        viewModel.activate()
        advanceUntilIdle()

        assertEquals(false, viewModel.uiState.value.isLoading)
        assertEquals(FolderAccessNotice.StorageFailure, viewModel.uiState.value.notice)
    }

    @Test
    fun addEmitsExactOpaqueLaunchOnceWhileBeginIsRunning() = runTest {
        val launch = FolderPickerLaunch(
            requestToken = "opaque-request-token",
            initialTreeUri = null,
        )
        val beginGate = CompletableDeferred<Unit>()
        val repository = FakeFolderMappingRepository().apply {
            beginAddResult = BeginFolderPickerResult.Started(launch)
            this.beginGate = beginGate
        }
        val viewModel = FolderAccessViewModel(repository)
        viewModel.activate()
        runCurrent()

        viewModel.addFolder()
        viewModel.addFolder()
        runCurrent()

        assertEquals(1, repository.beginAddCalls)
        beginGate.complete(Unit)
        advanceUntilIdle()
        assertEquals(launch, viewModel.pickerLaunches.first())
    }

    @Test
    fun beginOutcomesHaveDistinctNoticesAndRepairForwardsMappingId() = runTest {
        val repository = FakeFolderMappingRepository()
        val viewModel = FolderAccessViewModel(repository)
        viewModel.activate()
        runCurrent()

        val addCases = listOf(
            BeginFolderPickerResult.Busy to FolderAccessNotice.PickerBusy,
            BeginFolderPickerResult.MappingNotFound to FolderAccessNotice.MappingMissing,
            BeginFolderPickerResult.StorageFailure to FolderAccessNotice.StorageFailure,
        )
        addCases.forEach { (result, expectedNotice) ->
            repository.beginAddResult = result
            viewModel.addFolder()
            advanceUntilIdle()
            assertEquals(expectedNotice, viewModel.uiState.value.notice)
        }

        repository.beginRepairResult = BeginFolderPickerResult.MappingNotFound
        viewModel.repairFolder("mapping-a")
        advanceUntilIdle()

        assertEquals(listOf("mapping-a"), repository.repairCalls)
        assertEquals(FolderAccessNotice.MappingMissing, viewModel.uiState.value.notice)
    }

    @Test
    fun removeForwardsOneMappingAndExposesProgressUntilSuccessfulCompletion() = runTest {
        val removeGate = CompletableDeferred<Unit>()
        val repository = FakeFolderMappingRepository().apply {
            removeResult = RemoveFolderResult.Removed
            this.removeGate = removeGate
        }
        val viewModel = FolderAccessViewModel(repository)
        viewModel.activate()
        runCurrent()

        viewModel.removeFolder("mapping-a")
        viewModel.removeFolder("mapping-b")
        viewModel.addFolder()
        viewModel.repairFolder("mapping-c")
        runCurrent()

        assertEquals(listOf("mapping-a"), repository.removeCalls)
        assertEquals(0, repository.beginAddCalls)
        assertTrue(repository.repairCalls.isEmpty())
        assertEquals("mapping-a", viewModel.uiState.value.removingMappingId)

        removeGate.complete(Unit)
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.removingMappingId)
        assertEquals(FolderAccessNotice.FolderRemoved, viewModel.uiState.value.notice)
    }

    @Test
    fun everyRemoveOutcomeHasAnExplicitNoticeAndClearsProgress() = runTest {
        val repository = FakeFolderMappingRepository()
        val viewModel = FolderAccessViewModel(repository)
        viewModel.activate()
        runCurrent()
        val cases = listOf(
            RemoveFolderResult.MappingNotFound to FolderAccessNotice.MappingMissing,
            RemoveFolderResult.Busy to FolderAccessNotice.PickerBusy,
            RemoveFolderResult.GrantFailure to FolderAccessNotice.RemovalGrantFailure,
            RemoveFolderResult.StorageFailure to FolderAccessNotice.StorageFailure,
        )

        cases.forEachIndexed { index, (result, expectedNotice) ->
            repository.removeResult = result

            viewModel.removeFolder("mapping-$index")
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.removingMappingId)
            assertEquals(expectedNotice, viewModel.uiState.value.notice)
        }
        assertEquals(cases.indices.map { "mapping-$it" }, repository.removeCalls)
    }

    @Test
    fun removeExceptionBecomesNonSensitiveStorageNotice() = runTest {
        val repository = FakeFolderMappingRepository().apply {
            removeFailure = IllegalStateException("sensitive provider detail")
        }
        val viewModel = FolderAccessViewModel(repository)
        viewModel.activate()
        runCurrent()

        viewModel.removeFolder("mapping-a")
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.removingMappingId)
        assertEquals(FolderAccessNotice.StorageFailure, viewModel.uiState.value.notice)
    }

    @Test
    fun deactivationCancelsRemovalClearsProgressAndBlocksFurtherRemoval() = runTest {
        val removeGate = CompletableDeferred<Unit>()
        val repository = FakeFolderMappingRepository().apply {
            removeResult = RemoveFolderResult.Removed
            this.removeGate = removeGate
        }
        val viewModel = FolderAccessViewModel(repository)
        viewModel.activate()
        runCurrent()

        viewModel.removeFolder("mapping-a")
        runCurrent()
        assertEquals("mapping-a", viewModel.uiState.value.removingMappingId)

        viewModel.deactivate()
        advanceUntilIdle()
        viewModel.removeFolder("mapping-b")
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.removingMappingId)
        assertNull(viewModel.uiState.value.notice)
        assertEquals(listOf("mapping-a"), repository.removeCalls)
    }

    @Test
    fun lateCancelledRemovalCannotClearANewerRemoval() = runTest {
        val firstEntered = CompletableDeferred<Unit>()
        val finishCancelledFirst = CompletableDeferred<Unit>()
        val finishSecond = CompletableDeferred<Unit>()
        val repository = FakeFolderMappingRepository().apply {
            removeHandler = { mappingId ->
                when (mappingId) {
                    "mapping-a" -> {
                        firstEntered.complete(Unit)
                        withContext(NonCancellable) {
                            finishCancelledFirst.await()
                        }
                        throw CancellationException("cancelled first removal")
                    }
                    "mapping-b" -> {
                        finishSecond.await()
                        RemoveFolderResult.Removed
                    }
                    else -> RemoveFolderResult.Removed
                }
            }
        }
        val viewModel = FolderAccessViewModel(repository)
        viewModel.activate()
        runCurrent()

        viewModel.removeFolder("mapping-a")
        runCurrent()
        assertTrue(firstEntered.isCompleted)

        viewModel.deactivate()
        viewModel.activate()
        viewModel.removeFolder("mapping-b")
        runCurrent()
        assertEquals("mapping-b", viewModel.uiState.value.removingMappingId)

        finishCancelledFirst.complete(Unit)
        runCurrent()

        assertEquals("mapping-b", viewModel.uiState.value.removingMappingId)
        viewModel.removeFolder("mapping-c")
        runCurrent()
        assertEquals(listOf("mapping-a", "mapping-b"), repository.removeCalls)

        finishSecond.complete(Unit)
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.removingMappingId)
        assertEquals(FolderAccessNotice.FolderRemoved, viewModel.uiState.value.notice)
    }

    @Test
    fun enabledUpdatesRunIndependentlyPerMappingAndExposeStableProgress() = runTest {
        val firstResult = CompletableDeferred<SetFolderEnabledResult>()
        val secondResult = CompletableDeferred<SetFolderEnabledResult>()
        val repository = FakeFolderMappingRepository().apply {
            setEnabledHandler = { mappingId, _ ->
                when (mappingId) {
                    "mapping-a" -> firstResult.await()
                    "mapping-b" -> secondResult.await()
                    else -> SetFolderEnabledResult.MappingNotFound
                }
            }
        }
        val viewModel = FolderAccessViewModel(repository)
        viewModel.activate()
        runCurrent()

        viewModel.setFolderEnabled("mapping-a", false)
        viewModel.setFolderEnabled("mapping-b", true)
        runCurrent()

        assertEquals(
            listOf("mapping-a" to false, "mapping-b" to true),
            repository.setEnabledCalls,
        )
        assertEquals(
            setOf("mapping-a", "mapping-b"),
            viewModel.uiState.value.updatingEnabledMappingIds,
        )

        firstResult.complete(SetFolderEnabledResult.Updated)
        runCurrent()
        assertEquals(setOf("mapping-b"), viewModel.uiState.value.updatingEnabledMappingIds)

        secondResult.complete(SetFolderEnabledResult.Updated)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.updatingEnabledMappingIds.isEmpty())
        assertNull(viewModel.uiState.value.notice)
    }

    @Test
    fun enabledUpdateFailuresHaveTypedNonSensitiveNotices() = runTest {
        val repository = FakeFolderMappingRepository()
        val viewModel = FolderAccessViewModel(repository)
        viewModel.activate()
        runCurrent()

        val cases = listOf(
            SetFolderEnabledResult.MappingNotFound to FolderAccessNotice.MappingMissing,
            SetFolderEnabledResult.StorageFailure to FolderAccessNotice.StorageFailure,
        )
        cases.forEachIndexed { index, (result, expectedNotice) ->
            repository.setEnabledResult = result
            viewModel.setFolderEnabled("mapping-$index", enabled = index % 2 == 0)
            advanceUntilIdle()

            assertEquals(expectedNotice, viewModel.uiState.value.notice)
            assertTrue(viewModel.uiState.value.updatingEnabledMappingIds.isEmpty())
        }

        repository.setEnabledFailure = IllegalStateException("sensitive storage detail")
        viewModel.setFolderEnabled("mapping-exception", false)
        advanceUntilIdle()

        assertEquals(FolderAccessNotice.StorageFailure, viewModel.uiState.value.notice)
        assertTrue(viewModel.uiState.value.updatingEnabledMappingIds.isEmpty())
    }

    @Test
    fun lateCancelledEnabledUpdateCannotOverwriteOrClearReplacement() = runTest {
        val firstEntered = CompletableDeferred<Unit>()
        val finishCancelledFirst = CompletableDeferred<Unit>()
        val finishReplacement = CompletableDeferred<Unit>()
        val repository = FakeFolderMappingRepository().apply {
            setEnabledHandler = { _, enabled ->
                if (!enabled) {
                    firstEntered.complete(Unit)
                    withContext(NonCancellable) {
                        finishCancelledFirst.await()
                    }
                    SetFolderEnabledResult.StorageFailure
                } else {
                    finishReplacement.await()
                    SetFolderEnabledResult.Updated
                }
            }
        }
        val viewModel = FolderAccessViewModel(repository)
        viewModel.activate()
        runCurrent()

        viewModel.setFolderEnabled("mapping-a", false)
        runCurrent()
        assertTrue(firstEntered.isCompleted)

        viewModel.setFolderEnabled("mapping-a", true)
        runCurrent()
        assertEquals(setOf("mapping-a"), viewModel.uiState.value.updatingEnabledMappingIds)

        finishCancelledFirst.complete(Unit)
        runCurrent()
        assertEquals(setOf("mapping-a"), viewModel.uiState.value.updatingEnabledMappingIds)
        assertNull(viewModel.uiState.value.notice)

        finishReplacement.complete(Unit)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.updatingEnabledMappingIds.isEmpty())
        assertNull(viewModel.uiState.value.notice)
        assertEquals(
            listOf("mapping-a" to false, "mapping-a" to true),
            repository.setEnabledCalls,
        )
    }

    @Test
    fun deactivationInvalidatesEnabledUpdateAndBlocksNewOnes() = runTest {
        val finishCancelledUpdate = CompletableDeferred<Unit>()
        val repository = FakeFolderMappingRepository().apply {
            setEnabledHandler = { _, _ ->
                withContext(NonCancellable) {
                    finishCancelledUpdate.await()
                }
                SetFolderEnabledResult.StorageFailure
            }
        }
        val viewModel = FolderAccessViewModel(repository)
        viewModel.activate()
        runCurrent()

        viewModel.setFolderEnabled("mapping-a", false)
        runCurrent()
        assertEquals(setOf("mapping-a"), viewModel.uiState.value.updatingEnabledMappingIds)

        viewModel.deactivate()
        viewModel.setFolderEnabled("mapping-b", true)
        assertTrue(viewModel.uiState.value.updatingEnabledMappingIds.isEmpty())

        finishCancelledUpdate.complete(Unit)
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.notice)
        assertEquals(listOf("mapping-a" to false), repository.setEnabledCalls)
    }

    @Test
    fun everyPickerCompletionHasAnExplicitNoticeAndForwardsExactSelection() = runTest {
        val repository = FakeFolderMappingRepository()
        val viewModel = FolderAccessViewModel(repository)
        runCurrent()
        val selection = FolderPickerSelection.Selected(
            treeUri = "content://provider.test/tree/selected",
            grantedFlags = 65,
        )
        val cases = listOf(
            FolderPickerCompletion.Added("mapping-a") to FolderAccessNotice.FolderAdded,
            FolderPickerCompletion.Repaired("mapping-a") to FolderAccessNotice.FolderRepaired,
            FolderPickerCompletion.Cancelled to null,
            FolderPickerCompletion.Stale to FolderAccessNotice.SelectionExpired,
            FolderPickerCompletion.InvalidSelection to FolderAccessNotice.InvalidSelection,
            FolderPickerCompletion.ReadPermissionMissing to FolderAccessNotice.ReadPermissionMissing,
            FolderPickerCompletion.Duplicate to FolderAccessNotice.DuplicateFolder,
            FolderPickerCompletion.GrantFailure to FolderAccessNotice.GrantFailure,
            FolderPickerCompletion.StorageFailure to FolderAccessNotice.StorageFailure,
            FolderPickerCompletion.CleanupIncomplete to FolderAccessNotice.CleanupIncomplete,
        )

        cases.forEach { (completion, expectedNotice) ->
            repository.completionResult = completion

            assertEquals(
                completion,
                viewModel.completePicker("opaque-request-token", selection),
            )
            assertEquals(expectedNotice, viewModel.uiState.value.notice)
        }

        assertTrue(repository.completionCalls.all { it.first == "opaque-request-token" })
        assertTrue(repository.completionCalls.all { it.second == selection })
    }

    @Test
    fun cancellationPropagatesWithoutBeingConvertedToAUserNotice() = runTest {
        val repository = FakeFolderMappingRepository().apply {
            completionFailure = CancellationException("test cancellation")
        }
        val viewModel = FolderAccessViewModel(repository)
        runCurrent()
        var cancellation: CancellationException? = null

        try {
            viewModel.completePicker("opaque-request-token", FolderPickerSelection.Cancelled)
        } catch (error: CancellationException) {
            cancellation = error
        }

        assertTrue(cancellation != null)
        assertNull(viewModel.uiState.value.notice)
    }

    @Test
    fun inactiveViewModelDoesNotObserveOrBeginNewFolderWork() = runTest {
        val repository = FakeFolderMappingRepository().apply {
            beginAddResult = BeginFolderPickerResult.Started(
                FolderPickerLaunch("request-a", null),
            )
            beginRepairResult = BeginFolderPickerResult.Started(
                FolderPickerLaunch("request-b", null),
            )
        }
        val viewModel = FolderAccessViewModel(repository)

        viewModel.addFolder()
        viewModel.repairFolder("mapping-a")
        viewModel.removeFolder("mapping-a")
        viewModel.setFolderEnabled("mapping-a", false)
        advanceUntilIdle()

        assertEquals(0, repository.observeCalls)
        assertEquals(0, repository.beginAddCalls)
        assertTrue(repository.repairCalls.isEmpty())
        assertTrue(repository.removeCalls.isEmpty())
        assertTrue(repository.setEnabledCalls.isEmpty())
    }

    @Test
    fun activationIsIdempotentAndDeactivationBlocksFurtherActions() = runTest {
        val repository = FakeFolderMappingRepository()
        val viewModel = FolderAccessViewModel(repository)

        viewModel.activate()
        viewModel.activate()
        runCurrent()
        assertEquals(1, repository.observeCalls)

        viewModel.deactivate()
        repository.beginAddResult = BeginFolderPickerResult.StorageFailure
        viewModel.addFolder()
        viewModel.removeFolder("mapping-a")
        advanceUntilIdle()

        assertEquals(0, repository.beginAddCalls)
        assertTrue(repository.removeCalls.isEmpty())
    }
}

private class FakeFolderMappingRepository : FolderMappingRepository {
    val mappings = MutableStateFlow<List<FolderMapping>>(emptyList())
    var observationFailure: Throwable? = null
    var beginAddResult: BeginFolderPickerResult = BeginFolderPickerResult.StorageFailure
    var beginRepairResult: BeginFolderPickerResult = BeginFolderPickerResult.StorageFailure
    var completionResult: FolderPickerCompletion = FolderPickerCompletion.StorageFailure
    var completionFailure: Throwable? = null
    var removeResult: RemoveFolderResult = RemoveFolderResult.StorageFailure
    var removeFailure: Throwable? = null
    var removeHandler: (suspend (String) -> RemoveFolderResult)? = null
    var beginGate: CompletableDeferred<Unit>? = null
    var removeGate: CompletableDeferred<Unit>? = null
    var setEnabledResult: SetFolderEnabledResult = SetFolderEnabledResult.StorageFailure
    var setEnabledFailure: Throwable? = null
    var setEnabledHandler: (suspend (String, Boolean) -> SetFolderEnabledResult)? = null
    val validateResults = mutableMapOf<String, ValidateFolderAccessResult>()
    var validateHandler: (suspend (String) -> ValidateFolderAccessResult)? = null
    val beginScanResults = mutableMapOf<String, BeginFolderScanResult>()
    var beginScanHandler: (suspend (String) -> BeginFolderScanResult)? = null
    var observeCalls = 0
    var beginAddCalls = 0
    val repairCalls = mutableListOf<String>()
    val completionCalls = mutableListOf<Pair<String, FolderPickerSelection>>()
    val removeCalls = mutableListOf<String>()
    val setEnabledCalls = mutableListOf<Pair<String, Boolean>>()
    val validateCalls = mutableListOf<String>()
    val beginScanCalls = mutableListOf<String>()

    override fun observeMappings(): Flow<List<FolderMapping>> {
        observeCalls += 1
        return observationFailure?.let { error ->
            flow { throw error }
        } ?: mappings
    }

    override suspend fun beginAdd(): BeginFolderPickerResult {
        beginAddCalls += 1
        beginGate?.await()
        return beginAddResult
    }

    override suspend fun beginRepair(mappingId: String): BeginFolderPickerResult {
        repairCalls += mappingId
        return beginRepairResult
    }

    override suspend fun completePicker(
        requestToken: String,
        selection: FolderPickerSelection,
    ): FolderPickerCompletion {
        completionCalls += requestToken to selection
        completionFailure?.let { throw it }
        return completionResult
    }

    override suspend fun prepareForSignOut(): PendingFolderCleanupResult =
        PendingFolderCleanupResult.Complete

    override suspend fun validate(mappingId: String): ValidateFolderAccessResult {
        validateCalls += mappingId
        validateHandler?.let { return it(mappingId) }
        return validateResults.getOrElse(mappingId) {
            ValidateFolderAccessResult.Found(FolderAccessHealth.Ready)
        }
    }

    override suspend fun beginScan(mappingId: String): BeginFolderScanResult {
        beginScanCalls += mappingId
        beginScanHandler?.let { return it(mappingId) }
        return beginScanResults.getOrElse(mappingId) {
            BeginFolderScanResult.Ready(flowOf())
        }
    }

    override suspend fun setEnabled(
        mappingId: String,
        enabled: Boolean,
    ): SetFolderEnabledResult {
        setEnabledCalls += mappingId to enabled
        setEnabledHandler?.let { handler -> return handler(mappingId, enabled) }
        setEnabledFailure?.let { throw it }
        return setEnabledResult
    }

    override suspend fun remove(mappingId: String): RemoveFolderResult {
        removeCalls += mappingId
        removeHandler?.let { handler -> return handler(mappingId) }
        removeGate?.await()
        removeFailure?.let { throw it }
        return removeResult
    }
}
