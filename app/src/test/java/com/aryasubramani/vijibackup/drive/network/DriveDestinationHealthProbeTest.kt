package com.aryasubramani.vijibackup.drive.network

import com.aryasubramani.vijibackup.drive.config.DriveConfiguration
import com.aryasubramani.vijibackup.drive.domain.DriveConnectionResult
import com.aryasubramani.vijibackup.drive.google.DriveAccessToken
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class DriveDestinationHealthProbeTest {
    @Test
    fun invalidConfigurationFailsBeforeNetworkAccess() = runTest {
        var calls = 0
        val probe = HttpDriveDestinationHealthProbe(
            configuration = DriveConfiguration.Invalid,
            httpClient = DriveDestinationHttpClient { _, _ ->
                calls += 1
                healthyResponse()
            },
        )

        assertEquals(
            DriveConnectionResult.ConfigurationRequired,
            probe.check(token()),
        )
        assertEquals(0, calls)
    }

    @Test
    fun configuredFolderAndEphemeralTokenReachTheClientExactlyOnce() = runTest {
        val token = token()
        var observedFolderId: String? = null
        var observedToken: DriveAccessToken? = null
        val probe = HttpDriveDestinationHealthProbe(
            configuration = DriveConfiguration.Ready("configured-folder"),
            httpClient = DriveDestinationHttpClient { folderId, accessToken ->
                observedFolderId = folderId
                observedToken = accessToken
                healthyResponse()
            },
        )

        assertEquals(DriveConnectionResult.Ready, probe.check(token))
        assertEquals("configured-folder", observedFolderId)
        assertSame(token, observedToken)
    }

    @Test
    fun ioFailureIsTemporaryAndDoesNotEscape() = runTest {
        val probe = HttpDriveDestinationHealthProbe(
            configuration = DriveConfiguration.Ready("configured-folder"),
            httpClient = DriveDestinationHttpClient { _, _ -> throw IOException("offline") },
        )

        assertEquals(DriveConnectionResult.TemporaryFailure, probe.check(token()))
    }

    @Test
    fun coroutineCancellationIsNeverConvertedToAHealthResult() {
        val probe = HttpDriveDestinationHealthProbe(
            configuration = DriveConfiguration.Ready("configured-folder"),
            httpClient = DriveDestinationHttpClient { _, _ ->
                throw CancellationException("cancelled")
            },
        )

        assertThrows(CancellationException::class.java) {
            kotlinx.coroutines.runBlocking { probe.check(token()) }
        }
    }

    @Test
    fun responseClassifierRemainsTheSingleStatusPolicy() = runTest {
        val probe = HttpDriveDestinationHealthProbe(
            configuration = DriveConfiguration.Ready("configured-folder"),
            httpClient = DriveDestinationHttpClient { _, _ ->
                DriveDestinationHttpResponse(statusCode = 429, body = null)
            },
        )

        assertEquals(DriveConnectionResult.TemporaryFailure, probe.check(token()))
    }

    private fun token(): DriveAccessToken =
        requireNotNull(DriveAccessToken.create("ephemeral-token"))

    private fun healthyResponse() = DriveDestinationHttpResponse(
        statusCode = 200,
        body = """{
          "id":"configured-folder",
          "mimeType":"$DRIVE_FOLDER_MIME_TYPE",
          "trashed":false,
          "capabilities":{"canAddChildren":true,"canListChildren":true}
        }""".trimIndent(),
    )
}
