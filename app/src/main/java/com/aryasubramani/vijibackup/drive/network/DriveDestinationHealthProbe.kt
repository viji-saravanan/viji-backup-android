package com.aryasubramani.vijibackup.drive.network

import com.aryasubramani.vijibackup.drive.config.DriveConfiguration
import com.aryasubramani.vijibackup.drive.domain.DriveConnectionResult
import com.aryasubramani.vijibackup.drive.google.DriveAccessToken
import java.io.IOException
import kotlinx.coroutines.CancellationException

internal fun interface DriveDestinationHealthProbe {
    suspend fun check(accessToken: DriveAccessToken): DriveConnectionResult
}

internal fun interface DriveDestinationHttpClient {
    suspend fun getMetadata(
        folderId: String,
        accessToken: DriveAccessToken,
    ): DriveDestinationHttpResponse
}

internal class HttpDriveDestinationHealthProbe(
    private val configuration: DriveConfiguration,
    private val httpClient: DriveDestinationHttpClient,
) : DriveDestinationHealthProbe {
    override suspend fun check(accessToken: DriveAccessToken): DriveConnectionResult {
        val readyConfiguration = configuration as? DriveConfiguration.Ready
            ?: return DriveConnectionResult.ConfigurationRequired
        val response = try {
            httpClient.getMetadata(
                folderId = readyConfiguration.folderId,
                accessToken = accessToken,
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: IOException) {
            return DriveConnectionResult.TemporaryFailure
        }
        return classifyDriveDestinationResponse(
            configuredFolderId = readyConfiguration.folderId,
            response = response,
        )
    }
}
