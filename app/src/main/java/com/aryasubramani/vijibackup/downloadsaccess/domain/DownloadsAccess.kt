package com.aryasubramani.vijibackup.downloadsaccess.domain

import com.aryasubramani.vijibackup.downloadsaccess.data.DownloadsSourceConfiguration
import com.aryasubramani.vijibackup.downloadsaccess.data.DownloadsSourceStore
import kotlinx.coroutines.CancellationException

enum class DownloadsAccessPlatform {
    AllFiles,
    SafPicker,
}

interface DownloadsAccessProbe {
    val platform: DownloadsAccessPlatform

    fun hasAccess(): Boolean

    fun isPrimaryStorageAvailable(): Boolean

    fun isDownloadsRootReadable(): Boolean
}

enum class DownloadsAccessHealth {
    NotConfigured,
    PermissionGrantedButUnused,
    NeedsPermission,
    StorageUnavailable,
    Disabled,
    Ready,
    UseSafPicker,
}

data class DownloadsAccessSnapshot(
    val configuration: DownloadsSourceConfiguration,
    val health: DownloadsAccessHealth,
)

sealed interface DownloadsAccessResult {
    data class Success(val snapshot: DownloadsAccessSnapshot) : DownloadsAccessResult

    data object PersistenceFailure : DownloadsAccessResult
}

class DownloadsAccessManager(
    private val store: DownloadsSourceStore,
    private val accessProbe: DownloadsAccessProbe,
) {
    suspend fun refresh(): DownloadsAccessResult {
        val configuration = readConfiguration()
            ?: return DownloadsAccessResult.PersistenceFailure
        return success(configuration)
    }

    suspend fun configureFromCurrentPermission(): DownloadsAccessResult {
        val configuration = readConfiguration()
            ?: return DownloadsAccessResult.PersistenceFailure
        if (accessProbe.platform == DownloadsAccessPlatform.SafPicker) {
            return success(configuration)
        }
        if (!probeOrFalse(accessProbe::hasAccess)) {
            return success(configuration)
        }

        val configured = DownloadsSourceConfiguration(
            configured = true,
            enabled = if (configuration.configured) configuration.enabled else true,
        )
        if (!writeConfiguration(configured)) {
            return DownloadsAccessResult.PersistenceFailure
        }
        return success(configured)
    }

    suspend fun setEnabled(enabled: Boolean): DownloadsAccessResult {
        val configuration = readConfiguration()
            ?: return DownloadsAccessResult.PersistenceFailure
        if (!configuration.configured) return success(configuration)

        val updated = configuration.copy(enabled = enabled)
        if (!writeConfiguration(updated)) {
            return DownloadsAccessResult.PersistenceFailure
        }
        return success(updated)
    }

    suspend fun remove(): DownloadsAccessResult {
        val removed = DownloadsSourceConfiguration()
        if (!writeConfiguration(removed)) {
            return DownloadsAccessResult.PersistenceFailure
        }
        return success(removed)
    }

    private fun success(configuration: DownloadsSourceConfiguration) =
        DownloadsAccessResult.Success(
            DownloadsAccessSnapshot(
                configuration = configuration,
                health = classify(configuration),
            ),
        )

    private fun classify(configuration: DownloadsSourceConfiguration): DownloadsAccessHealth {
        if (accessProbe.platform == DownloadsAccessPlatform.SafPicker) {
            return DownloadsAccessHealth.UseSafPicker
        }

        val accessGranted = probeOrFalse(accessProbe::hasAccess)
        if (!configuration.configured) {
            return if (accessGranted) {
                DownloadsAccessHealth.PermissionGrantedButUnused
            } else {
                DownloadsAccessHealth.NotConfigured
            }
        }
        if (!accessGranted) return DownloadsAccessHealth.NeedsPermission
        if (!configuration.enabled) return DownloadsAccessHealth.Disabled
        if (!probeOrFalse(accessProbe::isPrimaryStorageAvailable)) {
            return DownloadsAccessHealth.StorageUnavailable
        }
        if (!probeOrFalse(accessProbe::isDownloadsRootReadable)) {
            return DownloadsAccessHealth.StorageUnavailable
        }
        return DownloadsAccessHealth.Ready
    }

    private suspend fun readConfiguration(): DownloadsSourceConfiguration? =
        captureFailure { store.read() }.getOrNull()

    private suspend fun writeConfiguration(configuration: DownloadsSourceConfiguration): Boolean =
        captureFailure { store.write(configuration) }.isSuccess

    private fun probeOrFalse(block: () -> Boolean): Boolean = try {
        block()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        false
    }
}

private suspend fun <T> captureFailure(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (failure: Exception) {
    Result.failure(failure)
}
