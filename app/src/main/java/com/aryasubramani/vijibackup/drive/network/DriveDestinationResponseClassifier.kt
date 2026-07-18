package com.aryasubramani.vijibackup.drive.network

import com.aryasubramani.vijibackup.drive.domain.DriveConnectionResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject

internal const val DRIVE_FOLDER_MIME_TYPE = "application/vnd.google-apps.folder"

internal data class DriveDestinationHttpResponse(
    val statusCode: Int,
    val body: String?,
)

internal fun classifyDriveDestinationResponse(
    configuredFolderId: String,
    response: DriveDestinationHttpResponse,
): DriveConnectionResult = when (response.statusCode) {
    200 -> classifySuccessfulResponse(configuredFolderId, response.body)
    401 -> DriveConnectionResult.NeedsAuthorization
    403, 404 -> DriveConnectionResult.DestinationMissingOrInaccessible
    408, 425, 429 -> DriveConnectionResult.TemporaryFailure
    in 500..599 -> DriveConnectionResult.TemporaryFailure
    else -> DriveConnectionResult.InvalidResponse
}

private fun classifySuccessfulResponse(
    configuredFolderId: String,
    body: String?,
): DriveConnectionResult {
    if (body.isNullOrBlank()) return DriveConnectionResult.InvalidResponse

    val metadata = try {
        Json.parseToJsonElement(body).jsonObject
    } catch (_: IllegalArgumentException) {
        return DriveConnectionResult.InvalidResponse
    }
    val id = metadata.requiredString("id")
        ?: return DriveConnectionResult.InvalidResponse
    val mimeType = metadata.requiredString("mimeType")
        ?: return DriveConnectionResult.InvalidResponse
    val trashed = metadata.requiredBoolean("trashed")
        ?: return DriveConnectionResult.InvalidResponse
    val capabilities = metadata["capabilities"] as? JsonObject
        ?: return DriveConnectionResult.InvalidResponse
    val canAddChildren = capabilities.requiredBoolean("canAddChildren")
        ?: return DriveConnectionResult.InvalidResponse
    val canListChildren = capabilities.requiredBoolean("canListChildren")
        ?: return DriveConnectionResult.InvalidResponse

    return when {
        id != configuredFolderId -> DriveConnectionResult.InvalidResponse
        mimeType != DRIVE_FOLDER_MIME_TYPE -> DriveConnectionResult.DestinationNotFolder
        trashed -> DriveConnectionResult.DestinationTrashed
        !canAddChildren || !canListChildren -> DriveConnectionResult.DestinationReadOnly
        else -> DriveConnectionResult.Ready
    }
}

private fun JsonObject.requiredString(name: String): String? =
    (this[name] as? JsonPrimitive)
        ?.takeIf(JsonPrimitive::isString)
        ?.content

private fun JsonObject.requiredBoolean(name: String): Boolean? =
    (this[name] as? JsonPrimitive)
        ?.takeUnless(JsonPrimitive::isString)
        ?.booleanOrNull
