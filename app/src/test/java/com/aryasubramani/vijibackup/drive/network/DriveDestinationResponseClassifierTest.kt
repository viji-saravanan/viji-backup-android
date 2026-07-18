package com.aryasubramani.vijibackup.drive.network

import com.aryasubramani.vijibackup.drive.domain.DriveConnectionResult
import org.junit.Assert.assertEquals
import org.junit.Test

class DriveDestinationResponseClassifierTest {
    @Test
    fun writableListableFolderIsReady() {
        assertClassified(
            expected = DriveConnectionResult.Ready,
            body = healthyBody(),
        )
    }

    @Test
    fun extraResponseFieldsAreIgnored() {
        assertClassified(
            expected = DriveConnectionResult.Ready,
            body = healthyBody(extra = ", \"name\": \"not logged\""),
        )
    }

    @Test
    fun mismatchedOrMissingReturnedIdIsInvalid() {
        assertClassified(
            expected = DriveConnectionResult.InvalidResponse,
            body = healthyBody(folderId = "another-folder"),
        )
        assertClassified(
            expected = DriveConnectionResult.InvalidResponse,
            body = healthyBody().replace("\"id\": \"configured-folder\",", ""),
        )
    }

    @Test
    fun nonFolderAndTrashedFolderAreDistinct() {
        assertClassified(
            expected = DriveConnectionResult.DestinationNotFolder,
            body = healthyBody(mimeType = "application/octet-stream"),
        )
        assertClassified(
            expected = DriveConnectionResult.DestinationTrashed,
            body = healthyBody(trashed = true),
        )
    }

    @Test
    fun eitherMissingWriteOrListCapabilityIsReadOnly() {
        assertClassified(
            expected = DriveConnectionResult.DestinationReadOnly,
            body = healthyBody(canAddChildren = false),
        )
        assertClassified(
            expected = DriveConnectionResult.DestinationReadOnly,
            body = healthyBody(canListChildren = false),
        )
    }

    @Test
    fun missingOrWrongTypedRequiredMetadataIsInvalid() {
        listOf(
            healthyBody().replace("\"trashed\": false,", ""),
            healthyBody().replace("\"capabilities\":", "\"other\":"),
            healthyBody().replace("\"canAddChildren\": true,", ""),
            healthyBody().replace("\"canListChildren\": true", "\"canListChildren\": \"yes\""),
            healthyBody().replace("\"mimeType\": \"$DRIVE_FOLDER_MIME_TYPE\"", "\"mimeType\": 1"),
            "not-json",
            "",
        ).forEach { body ->
            assertClassified(DriveConnectionResult.InvalidResponse, body = body)
        }
    }

    @Test
    fun authorizationAndDestinationAccessStatusesAreTyped() {
        assertClassified(DriveConnectionResult.NeedsAuthorization, statusCode = 401)
        assertClassified(DriveConnectionResult.DestinationMissingOrInaccessible, statusCode = 403)
        assertClassified(DriveConnectionResult.DestinationMissingOrInaccessible, statusCode = 404)
    }

    @Test
    fun retryableHttpStatusesAreTemporary() {
        listOf(408, 425, 429, 500, 502, 503, 504, 599).forEach { statusCode ->
            assertClassified(
                expected = DriveConnectionResult.TemporaryFailure,
                statusCode = statusCode,
            )
        }
    }

    @Test
    fun unexpectedSuccessAndClientStatusesAreInvalid() {
        listOf(201, 204, 400, 409, 410, 422).forEach { statusCode ->
            assertClassified(
                expected = DriveConnectionResult.InvalidResponse,
                statusCode = statusCode,
            )
        }
    }

    private fun assertClassified(
        expected: DriveConnectionResult,
        statusCode: Int = 200,
        body: String? = null,
    ) {
        assertEquals(
            expected,
            classifyDriveDestinationResponse(
                configuredFolderId = "configured-folder",
                response = DriveDestinationHttpResponse(
                    statusCode = statusCode,
                    body = body,
                ),
            ),
        )
    }

    private fun healthyBody(
        folderId: String = "configured-folder",
        mimeType: String = DRIVE_FOLDER_MIME_TYPE,
        trashed: Boolean = false,
        canAddChildren: Boolean = true,
        canListChildren: Boolean = true,
        extra: String = "",
    ): String =
        """{
          "id": "$folderId",
          "mimeType": "$mimeType",
          "trashed": $trashed,
          "capabilities": {
            "canAddChildren": $canAddChildren,
            "canListChildren": $canListChildren
          }$extra
        }""".trimIndent()
}
