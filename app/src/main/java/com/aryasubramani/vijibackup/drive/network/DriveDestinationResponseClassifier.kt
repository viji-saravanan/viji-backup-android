package com.aryasubramani.vijibackup.drive.network

import com.aryasubramani.vijibackup.drive.domain.DriveConnectionResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject

internal const val DRIVE_FOLDER_MIME_TYPE = "application/vnd.google-apps.folder"

internal class DriveDestinationHttpResponse(
    val statusCode: Int,
    val body: String?,
    val bodyLimitExceeded: Boolean = false,
) {
    override fun toString(): String =
        "DriveDestinationHttpResponse(statusCode=$statusCode, body=REDACTED)"
}

internal fun classifyDriveDestinationResponse(
    configuredFolderId: String,
    response: DriveDestinationHttpResponse,
): DriveConnectionResult {
    if (response.bodyLimitExceeded) return DriveConnectionResult.InvalidResponse
    return when (response.statusCode) {
        200 -> classifySuccessfulResponse(configuredFolderId, response.body)
        401 -> DriveConnectionResult.NeedsAuthorization
        403 -> classifyForbiddenResponse(response.body)
        404 -> DriveConnectionResult.DestinationMissingOrInaccessible
        408, 425, 429 -> DriveConnectionResult.TemporaryFailure
        in 500..599 -> DriveConnectionResult.TemporaryFailure
        else -> DriveConnectionResult.InvalidResponse
    }
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

private fun classifyForbiddenResponse(body: String?): DriveConnectionResult {
    val reasons = forbiddenReasons(body)
    return when {
        reasons.any(RETRYABLE_FORBIDDEN_REASONS::contains) ->
            DriveConnectionResult.TemporaryFailure
        reasons.any(AUTHORIZATION_FORBIDDEN_REASONS::contains) ->
            DriveConnectionResult.NeedsAuthorization
        "insufficientFilePermissions" in reasons ->
            DriveConnectionResult.DestinationReadOnly
        reasons.any(QUOTA_FORBIDDEN_REASONS::contains) ->
            DriveConnectionResult.DestinationQuotaExceeded
        else -> DriveConnectionResult.DestinationMissingOrInaccessible
    }
}

private fun forbiddenReasons(body: String?): Set<String> {
    if (body.isNullOrBlank()) return emptySet()
    val root = try {
        Json.parseToJsonElement(body) as? JsonObject
    } catch (_: IllegalArgumentException) {
        null
    } ?: return emptySet()
    val error = root["error"] as? JsonObject ?: return emptySet()
    val errors = error["errors"] as? JsonArray ?: return emptySet()
    return errors.mapNotNullTo(mutableSetOf()) { entry ->
        (entry as? JsonObject)?.requiredString("reason")
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

private val RETRYABLE_FORBIDDEN_REASONS = setOf(
    "dailyLimitExceeded",
    "rateLimitExceeded",
    "sharingRateLimitExceeded",
    "userRateLimitExceeded",
)
private val AUTHORIZATION_FORBIDDEN_REASONS = setOf(
    "ACCESS_TOKEN_SCOPE_INSUFFICIENT",
    "authError",
    "insufficientPermissions",
)
private val QUOTA_FORBIDDEN_REASONS = setOf(
    "storageQuotaExceeded",
    "teamDriveFileLimitExceeded",
)
