package com.aryasubramani.vijibackup.downloadsaccess.domain

import com.aryasubramani.vijibackup.downloadsaccess.data.DownloadsSourceConfiguration
import com.aryasubramani.vijibackup.downloadsaccess.data.DownloadsSourceStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DownloadsAccessManagerTest {
    @Test
    fun unconfiguredSourceWithoutBroadAccessIsNotConfigured() = runTest {
        val result = createManager().refresh()

        assertSuccessHealth(DownloadsAccessHealth.NotConfigured, result)
    }

    @Test
    fun unconfiguredSourceWithBroadAccessReportsUnusedPermission() = runTest {
        val result = createManager(
            probe = FakeDownloadsAccessProbe(accessGranted = true),
        ).refresh()

        assertSuccessHealth(DownloadsAccessHealth.PermissionGrantedButUnused, result)
    }

    @Test
    fun configuredSourceWithoutBroadAccessNeedsPermission() = runTest {
        val result = createManager(
            store = FakeDownloadsSourceStore(configuredEnabled()),
        ).refresh()

        assertSuccessHealth(DownloadsAccessHealth.NeedsPermission, result)
    }

    @Test
    fun configuredDisabledSourceReportsDisabledAfterGrant() = runTest {
        val result = createManager(
            store = FakeDownloadsSourceStore(
                DownloadsSourceConfiguration(configured = true, enabled = false),
            ),
            probe = readyProbe(),
        ).refresh()

        assertSuccessHealth(DownloadsAccessHealth.Disabled, result)
    }

    @Test
    fun unavailablePrimaryStorageDoesNotReportReady() = runTest {
        val result = createManager(
            store = FakeDownloadsSourceStore(configuredEnabled()),
            probe = FakeDownloadsAccessProbe(
                accessGranted = true,
                primaryStorageAvailable = false,
            ),
        ).refresh()

        assertSuccessHealth(DownloadsAccessHealth.StorageUnavailable, result)
    }

    @Test
    fun unreadableDownloadsRootDoesNotReportReady() = runTest {
        val result = createManager(
            store = FakeDownloadsSourceStore(configuredEnabled()),
            probe = FakeDownloadsAccessProbe(
                accessGranted = true,
                downloadsRootReadable = false,
            ),
        ).refresh()

        assertSuccessHealth(DownloadsAccessHealth.StorageUnavailable, result)
    }

    @Test
    fun configuredEnabledGrantedReadableSourceIsReady() = runTest {
        val result = createManager(
            store = FakeDownloadsSourceStore(configuredEnabled()),
            probe = readyProbe(),
        ).refresh()

        assertSuccessHealth(DownloadsAccessHealth.Ready, result)
    }

    @Test
    fun androidTenAndEarlierUsesExistingSafPickerBoundary() = runTest {
        val probe = FakeDownloadsAccessProbe(
            platform = DownloadsAccessPlatform.SafPicker,
            accessGranted = true,
        )

        val result = createManager(probe = probe).refresh()

        assertSuccessHealth(DownloadsAccessHealth.UseSafPicker, result)
        assertEquals(0, probe.accessChecks)
        assertEquals(0, probe.storageChecks)
        assertEquals(0, probe.rootChecks)
    }

    @Test
    fun deniedSettingsReturnDoesNotCreateSourceConfiguration() = runTest {
        val store = FakeDownloadsSourceStore()
        val manager = createManager(store = store)

        val result = manager.configureFromCurrentPermission()

        assertSuccessHealth(DownloadsAccessHealth.NotConfigured, result)
        assertEquals(0, store.writeCount)
        assertEquals(DownloadsSourceConfiguration(), store.configuration)
    }

    @Test
    fun grantedSettingsReturnPersistsEnabledSourceBeforeReady() = runTest {
        val store = FakeDownloadsSourceStore()
        val manager = createManager(store = store, probe = readyProbe())

        val result = manager.configureFromCurrentPermission()

        assertSuccessHealth(DownloadsAccessHealth.Ready, result)
        assertEquals(1, store.writeCount)
        assertEquals(configuredEnabled(), store.configuration)
    }

    @Test
    fun repairPreservesDisabledChoice() = runTest {
        val store = FakeDownloadsSourceStore(
            DownloadsSourceConfiguration(configured = true, enabled = false),
        )
        val manager = createManager(store = store, probe = readyProbe())

        val result = manager.configureFromCurrentPermission()

        assertSuccessHealth(DownloadsAccessHealth.Disabled, result)
        assertEquals(
            DownloadsSourceConfiguration(configured = true, enabled = false),
            store.configuration,
        )
    }

    @Test
    fun externalRevocationIsReclassifiedOnRefresh() = runTest {
        val probe = readyProbe()
        val manager = createManager(
            store = FakeDownloadsSourceStore(configuredEnabled()),
            probe = probe,
        )
        assertSuccessHealth(DownloadsAccessHealth.Ready, manager.refresh())

        probe.accessGranted = false

        assertSuccessHealth(DownloadsAccessHealth.NeedsPermission, manager.refresh())
    }

    @Test
    fun disableAndEnablePersistIndependentlyFromPermission() = runTest {
        val store = FakeDownloadsSourceStore(configuredEnabled())
        val manager = createManager(store = store, probe = readyProbe())

        assertSuccessHealth(DownloadsAccessHealth.Disabled, manager.setEnabled(false))
        assertSuccessHealth(DownloadsAccessHealth.Ready, manager.setEnabled(true))
        assertEquals(2, store.writeCount)
        assertEquals(configuredEnabled(), store.configuration)
    }

    @Test
    fun settingEnabledBeforeConfigurationDoesNotCreateSource() = runTest {
        val store = FakeDownloadsSourceStore()
        val manager = createManager(store = store, probe = readyProbe())

        val result = manager.setEnabled(true)

        assertSuccessHealth(DownloadsAccessHealth.PermissionGrantedButUnused, result)
        assertEquals(0, store.writeCount)
    }

    @Test
    fun removeClearsConfigurationButCannotClaimAndroidPermissionWasRevoked() = runTest {
        val store = FakeDownloadsSourceStore(configuredEnabled())
        val manager = createManager(store = store, probe = readyProbe())

        val result = manager.remove()

        assertSuccessHealth(DownloadsAccessHealth.PermissionGrantedButUnused, result)
        assertEquals(DownloadsSourceConfiguration(), store.configuration)
        assertEquals(1, store.writeCount)
    }

    @Test
    fun persistenceFailuresAreTypedAndNeverReportReady() = runTest {
        val readFailure = createManager(
            store = FakeDownloadsSourceStore().apply {
                readFailure = IllegalStateException("test read failure")
            },
            probe = readyProbe(),
        )
        val writeFailure = createManager(
            store = FakeDownloadsSourceStore().apply {
                writeFailure = IllegalStateException("test write failure")
            },
            probe = readyProbe(),
        )

        assertEquals(DownloadsAccessResult.PersistenceFailure, readFailure.refresh())
        assertEquals(
            DownloadsAccessResult.PersistenceFailure,
            writeFailure.configureFromCurrentPermission(),
        )
    }

    @Test
    fun coroutineCancellationIsNeverConvertedToPersistenceFailure() {
        val manager = createManager(
            store = FakeDownloadsSourceStore().apply {
                readFailure = CancellationException("test cancellation")
            },
        )

        assertThrows(CancellationException::class.java) {
            runTest { manager.refresh() }
        }
    }
}

private fun createManager(
    store: FakeDownloadsSourceStore = FakeDownloadsSourceStore(),
    probe: FakeDownloadsAccessProbe = FakeDownloadsAccessProbe(),
) = DownloadsAccessManager(store = store, accessProbe = probe)

private fun configuredEnabled() = DownloadsSourceConfiguration(
    configured = true,
    enabled = true,
)

private fun readyProbe() = FakeDownloadsAccessProbe(
    accessGranted = true,
    primaryStorageAvailable = true,
    downloadsRootReadable = true,
)

private fun assertSuccessHealth(
    expected: DownloadsAccessHealth,
    result: DownloadsAccessResult,
) {
    assertEquals(
        expected,
        (result as DownloadsAccessResult.Success).snapshot.health,
    )
}

private class FakeDownloadsSourceStore(
    var configuration: DownloadsSourceConfiguration = DownloadsSourceConfiguration(),
) : DownloadsSourceStore {
    var readFailure: Throwable? = null
    var writeFailure: Throwable? = null
    var writeCount = 0

    override suspend fun read(): DownloadsSourceConfiguration {
        readFailure?.let { throw it }
        return configuration
    }

    override suspend fun write(configuration: DownloadsSourceConfiguration) {
        writeFailure?.let { throw it }
        writeCount += 1
        this.configuration = configuration
    }
}

private class FakeDownloadsAccessProbe(
    override val platform: DownloadsAccessPlatform = DownloadsAccessPlatform.AllFiles,
    var accessGranted: Boolean = false,
    var primaryStorageAvailable: Boolean = true,
    var downloadsRootReadable: Boolean = true,
) : DownloadsAccessProbe {
    var accessChecks = 0
    var storageChecks = 0
    var rootChecks = 0

    override fun hasAccess(): Boolean {
        accessChecks += 1
        return accessGranted
    }

    override fun isPrimaryStorageAvailable(): Boolean {
        storageChecks += 1
        return primaryStorageAvailable
    }

    override fun isDownloadsRootReadable(): Boolean {
        rootChecks += 1
        return downloadsRootReadable
    }
}
