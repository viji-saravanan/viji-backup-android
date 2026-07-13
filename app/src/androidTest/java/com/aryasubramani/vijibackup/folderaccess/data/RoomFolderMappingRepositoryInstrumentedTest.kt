package com.aryasubramani.vijibackup.folderaccess.data

import android.content.Context
import android.content.Intent
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aryasubramani.vijibackup.folderaccess.data.db.LocalFolderMappingEntity
import com.aryasubramani.vijibackup.folderaccess.data.db.PendingFolderOperationEntity
import com.aryasubramani.vijibackup.folderaccess.data.db.PendingFolderOperationState
import com.aryasubramani.vijibackup.folderaccess.data.db.PendingFolderOperationType
import com.aryasubramani.vijibackup.folderaccess.data.db.VijiBackupDatabase
import com.aryasubramani.vijibackup.folderaccess.domain.BeginFolderPickerResult
import com.aryasubramani.vijibackup.folderaccess.domain.FolderPickerCompletion
import com.aryasubramani.vijibackup.folderaccess.domain.FolderPickerSelection
import com.aryasubramani.vijibackup.folderaccess.saf.AcquireReadGrantResult
import com.aryasubramani.vijibackup.folderaccess.saf.GrantReleaseResult
import com.aryasubramani.vijibackup.folderaccess.saf.LocalFolderGrantManager
import com.aryasubramani.vijibackup.folderaccess.saf.PersistedFolderGrant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomFolderMappingRepositoryInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: VijiBackupDatabase
    private lateinit var grants: RecordingGrantManager
    private lateinit var repository: RoomFolderMappingRepository
    private val requestTokens = ArrayDeque(listOf("request-a", "request-b", "request-c"))
    private val mappingIds = ArrayDeque(listOf("mapping-a", "mapping-b", "mapping-c"))

    @Before
    fun setUp() {
        context.deleteDatabase(TEST_DATABASE_NAME)
        database = Room.databaseBuilder(
            context,
            VijiBackupDatabase::class.java,
            TEST_DATABASE_NAME,
        ).build()
        grants = RecordingGrantManager()
        repository = RoomFolderMappingRepository(
            dao = database.folderAccessDao(),
            grantManager = grants,
            ioDispatcher = Dispatchers.IO,
            requestTokenFactory = { requestTokens.removeFirst() },
            mappingIdFactory = { mappingIds.removeFirst() },
            clock = { NOW_EPOCH_MS },
        )
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(TEST_DATABASE_NAME)
    }

    @Test
    fun beginAndCancelAreDurableExclusiveAndHaveNoMappingSideEffect() = runTest {
        val started = repository.beginAdd() as BeginFolderPickerResult.Started

        assertEquals("request-a", started.request.requestToken)
        assertEquals(BeginFolderPickerResult.Busy, repository.beginAdd())
        assertEquals(
            FolderPickerCompletion.Cancelled,
            repository.completePicker(
                requestToken = started.request.requestToken,
                selection = FolderPickerSelection.Cancelled,
            ),
        )
        assertNull(database.folderAccessDao().pendingOperation())
        assertTrue(database.folderAccessDao().observeMappings().first().isEmpty())
        assertTrue(grants.acquireCalls.isEmpty())
        assertTrue(repository.beginAdd() is BeginFolderPickerResult.Started)
    }

    @Test
    fun staleSelectionIsIgnoredBeforeGrantAcquisition() = runTest {
        repository.beginAdd()

        assertEquals(
            FolderPickerCompletion.Stale,
            repository.completePicker(
                requestToken = "stale-token",
                selection = selected("content://provider.test/tree/stale"),
            ),
        )
        assertTrue(grants.acquireCalls.isEmpty())
        assertEquals("request-a", database.folderAccessDao().pendingOperation()?.requestToken)
    }

    @Test
    fun selectedTreeRequiresValidUriReadAndPersistableFlags() = runTest {
        val started = repository.beginAdd() as BeginFolderPickerResult.Started

        assertEquals(
            FolderPickerCompletion.InvalidSelection,
            repository.completePicker(
                started.request.requestToken,
                selected("https://provider.test/tree/not-content"),
            ),
        )
        assertTrue(grants.acquireCalls.isEmpty())
        assertNull(database.folderAccessDao().pendingOperation())

        val second = repository.beginAdd() as BeginFolderPickerResult.Started
        assertEquals(
            FolderPickerCompletion.ReadPermissionMissing,
            repository.completePicker(
                second.request.requestToken,
                FolderPickerSelection.Selected(
                    treeUri = "content://provider.test/tree/no-read",
                    grantedFlags = Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
                ),
            ),
        )
        assertTrue(grants.acquireCalls.isEmpty())
        assertNull(database.folderAccessDao().pendingOperation())
    }

    @Test
    fun successfulSelectionAcquiresReadAndCommitsOneMapping() = runTest {
        val started = repository.beginAdd() as BeginFolderPickerResult.Started
        val treeUri = "content://provider.test/tree/selected"

        assertEquals(
            FolderPickerCompletion.Added(mappingId = "mapping-a"),
            repository.completePicker(started.request.requestToken, selected(treeUri)),
        )

        assertEquals(listOf(treeUri), grants.acquireCalls.map { it.treeUri })
        assertNull(database.folderAccessDao().pendingOperation())
        val mapping = database.folderAccessDao().mappingById("mapping-a")
        requireNotNull(mapping)
        assertEquals(treeUri, mapping.treeUri)
        assertNull(mapping.sourceDisplayName)
        assertTrue(mapping.enabled)
    }

    @Test
    fun duplicateSelectionIsRejectedBeforeASecondGrantCall() = runTest {
        val first = repository.beginAdd() as BeginFolderPickerResult.Started
        val treeUri = "content://provider.test/tree/selected"
        assertTrue(repository.completePicker(first.request.requestToken, selected(treeUri)) is
            FolderPickerCompletion.Added)
        val second = repository.beginAdd() as BeginFolderPickerResult.Started

        assertEquals(
            FolderPickerCompletion.Duplicate,
            repository.completePicker(second.request.requestToken, selected(treeUri)),
        )
        assertEquals(1, grants.acquireCalls.size)
        assertNull(database.folderAccessDao().pendingOperation())
        assertEquals(1, database.folderAccessDao().observeMappings().first().size)
    }

    @Test
    fun cleanGrantRejectionLeavesNoStateAndDoesNotReleaseAnUnownedGrant() = runTest {
        grants.acquireResult = AcquireReadGrantResult.RejectedClean
        val started = repository.beginAdd() as BeginFolderPickerResult.Started
        val treeUri = "content://provider.test/tree/unavailable"

        assertEquals(
            FolderPickerCompletion.GrantFailure,
            repository.completePicker(
                started.request.requestToken,
                selected(treeUri),
            ),
        )
        assertTrue(database.folderAccessDao().observeMappings().first().isEmpty())
        assertNull(database.folderAccessDao().pendingOperation())
        assertTrue(grants.releaseCalls.isEmpty())
    }

    @Test
    fun uncertainGrantFailureRetainsTombstoneWhenCleanupCannotBeVerified() = runTest {
        grants.acquireResult = AcquireReadGrantResult.CleanupRequired
        grants.releaseResult = GrantReleaseResult.Failed
        val started = repository.beginAdd() as BeginFolderPickerResult.Started
        val treeUri = "content://provider.test/tree/uncertain"

        assertEquals(
            FolderPickerCompletion.CleanupIncomplete,
            repository.completePicker(
                started.request.requestToken,
                selected(treeUri),
            ),
        )
        assertTrue(database.folderAccessDao().observeMappings().first().isEmpty())
        val pending = requireNotNull(database.folderAccessDao().pendingOperation())
        assertEquals(PendingFolderOperationState.Abandoning, pending.state)
        assertEquals(treeUri, pending.selectedTreeUri)
        assertEquals(listOf(treeUri), grants.releaseCalls)
    }

    @Test
    fun requestedOperationAtExpiryBoundaryRemainsPending() = runTest {
        insertPending(
            operation = PendingFolderOperationType.Add,
            createdAtEpochMs = NOW_EPOCH_MS - PENDING_TIMEOUT_MS,
        )

        assertEquals(BeginFolderPickerResult.Busy, repository.beginAdd())
        assertEquals("pending-token", database.folderAccessDao().pendingOperation()?.requestToken)
        assertEquals(1, grants.persistedGrantReads)
    }

    @Test
    fun requestedOperationOlderThanExpiryIsRemovedBeforeNewWork() = runTest {
        insertPending(
            operation = PendingFolderOperationType.Add,
            createdAtEpochMs = NOW_EPOCH_MS - PENDING_TIMEOUT_MS - 1L,
        )

        assertTrue(repository.beginAdd() is BeginFolderPickerResult.Started)
        assertEquals("request-a", database.folderAccessDao().pendingOperation()?.requestToken)
        assertTrue(grants.releaseCalls.isEmpty())
    }

    @Test
    fun stagedSelectionWithoutPersistedGrantIsAbandonedBeforeNewWork() = runTest {
        val treeUri = "content://provider.test/tree/not-persisted"
        insertPending(
            operation = PendingFolderOperationType.Add,
            selectedTreeUri = treeUri,
        )

        assertTrue(repository.beginAdd() is BeginFolderPickerResult.Started)
        assertEquals("request-a", database.folderAccessDao().pendingOperation()?.requestToken)
        assertTrue(database.folderAccessDao().observeMappings().first().isEmpty())
        assertTrue(grants.releaseCalls.isEmpty())
    }

    @Test
    fun stagedAddWithPersistedReadGrantIsCommittedDuringInitialization() = runTest {
        val treeUri = "content://provider.test/tree/recover-add"
        insertPending(
            operation = PendingFolderOperationType.Add,
            selectedTreeUri = treeUri,
        )
        grants.persisted = listOf(readGrant(treeUri))

        val mappings = repository.observeMappings().first()

        assertEquals(listOf("mapping-a"), mappings.map { it.id })
        assertEquals(treeUri, database.folderAccessDao().mappingById("mapping-a")?.treeUri)
        assertNull(database.folderAccessDao().pendingOperation())
        assertTrue(grants.acquireCalls.isEmpty())
        assertTrue(grants.releaseCalls.isEmpty())
    }

    @Test
    fun stagedRepairWithPersistedReadGrantCommitsThenReleasesOldOrphan() = runTest {
        val oldTreeUri = "content://provider.test/tree/repair-old"
        val replacementTreeUri = "content://provider.test/tree/repair-new"
        database.folderAccessDao().insertMapping(mapping("existing-mapping", oldTreeUri))
        insertPending(
            operation = PendingFolderOperationType.Repair,
            targetMappingId = "existing-mapping",
            selectedTreeUri = replacementTreeUri,
        )
        grants.persisted = listOf(readGrant(oldTreeUri), readGrant(replacementTreeUri))

        repository.observeMappings().first()

        assertEquals(
            replacementTreeUri,
            database.folderAccessDao().mappingById("existing-mapping")?.treeUri,
        )
        assertNull(database.folderAccessDao().pendingOperation())
        assertEquals(listOf(oldTreeUri), grants.releaseCalls)
    }

    @Test
    fun stagedSameUriRepairNeverReleasesReferencedGrant() = runTest {
        val treeUri = "content://provider.test/tree/repair-same"
        database.folderAccessDao().insertMapping(mapping("existing-mapping", treeUri))
        insertPending(
            operation = PendingFolderOperationType.Repair,
            targetMappingId = "existing-mapping",
            selectedTreeUri = treeUri,
        )
        grants.persisted = listOf(readGrant(treeUri))

        repository.observeMappings().first()

        assertEquals(treeUri, database.folderAccessDao().mappingById("existing-mapping")?.treeUri)
        assertNull(database.folderAccessDao().pendingOperation())
        assertTrue(grants.releaseCalls.isEmpty())
    }

    @Test
    fun expiredStagedSelectionIsReleasedInsteadOfResumed() = runTest {
        val treeUri = "content://provider.test/tree/expired-selection"
        insertPending(
            operation = PendingFolderOperationType.Add,
            selectedTreeUri = treeUri,
            createdAtEpochMs = NOW_EPOCH_MS - PENDING_TIMEOUT_MS - 1L,
        )
        grants.persisted = listOf(readGrant(treeUri))

        assertTrue(repository.beginAdd() is BeginFolderPickerResult.Started)

        assertTrue(database.folderAccessDao().observeMappings().first().isEmpty())
        assertEquals(listOf(treeUri), grants.releaseCalls)
    }

    private suspend fun insertPending(
        operation: PendingFolderOperationType,
        targetMappingId: String? = null,
        selectedTreeUri: String? = null,
        createdAtEpochMs: Long = NOW_EPOCH_MS,
    ) {
        val dao = database.folderAccessDao()
        assertTrue(
            dao.tryBeginPendingOperation(
                PendingFolderOperationEntity(
                    requestToken = "pending-token",
                    operation = operation,
                    targetMappingId = targetMappingId,
                    selectedTreeUri = null,
                    state = PendingFolderOperationState.Requested,
                    createdAtEpochMs = createdAtEpochMs,
                ),
            ),
        )
        if (selectedTreeUri != null) {
            assertEquals(1, dao.markSelectionReceived("pending-token", selectedTreeUri))
        }
    }

    private fun mapping(id: String, treeUri: String) = LocalFolderMappingEntity(
        id = id,
        treeUri = treeUri,
        sourceDisplayName = "Test folder",
        enabled = true,
    )

    private fun readGrant(treeUri: String) = PersistedFolderGrant(
        treeUri = treeUri,
        hasReadAccess = true,
        hasWriteAccess = false,
    )

    private fun selected(treeUri: String) = FolderPickerSelection.Selected(
        treeUri = treeUri,
        grantedFlags =
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
    )

    private companion object {
        const val TEST_DATABASE_NAME = "folder_mapping_repository_test.db"
        const val PENDING_TIMEOUT_MS = 24L * 60L * 60L * 1_000L
        const val NOW_EPOCH_MS = PENDING_TIMEOUT_MS * 2L
    }
}

private class RecordingGrantManager : LocalFolderGrantManager {
    val acquireCalls = mutableListOf<FolderPickerSelection.Selected>()
    val releaseCalls = mutableListOf<String>()
    var acquireResult: AcquireReadGrantResult = AcquireReadGrantResult.Acquired
    var releaseResult: GrantReleaseResult = GrantReleaseResult.Released
    var persisted: List<PersistedFolderGrant> = emptyList()
    var persistedGrantReads = 0

    override suspend fun persistedGrants(): List<PersistedFolderGrant> {
        persistedGrantReads += 1
        return persisted
    }

    override suspend fun acquireReadGrant(
        treeUri: String,
        grantedFlags: Int,
    ): AcquireReadGrantResult {
        acquireCalls += FolderPickerSelection.Selected(treeUri, grantedFlags)
        return acquireResult
    }

    override suspend fun releaseGrant(treeUri: String): GrantReleaseResult {
        releaseCalls += treeUri
        return releaseResult
    }
}
