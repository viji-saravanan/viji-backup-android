package com.aryasubramani.vijibackup.drive.network

import com.aryasubramani.vijibackup.drive.google.DriveAccessToken
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

internal class UrlConnectionDriveDestinationHttpClient(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : DriveDestinationHttpClient {
    override suspend fun getMetadata(
        folderId: String,
        accessToken: DriveAccessToken,
    ): DriveDestinationHttpResponse = runInterruptible(ioDispatcher) {
        val connection = buildDriveDestinationMetadataUrl(folderId)
            .openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("Authorization", "Bearer ${accessToken.value}")

            val statusCode = connection.responseCode
            val responseBody = when (statusCode) {
                HttpURLConnection.HTTP_OK -> connection.inputStream
                    ?.let { stream -> readDriveResponseBody(stream, MAX_RESPONSE_BYTES) }
                    ?: DriveResponseBody.Empty
                HttpURLConnection.HTTP_FORBIDDEN -> connection.errorStream
                    ?.let { stream -> readDriveResponseBody(stream, MAX_RESPONSE_BYTES) }
                    ?: DriveResponseBody.Empty
                else -> {
                    connection.errorStream?.close()
                    DriveResponseBody.Empty
                }
            }
            DriveDestinationHttpResponse(
                statusCode = statusCode,
                body = responseBody.body,
                bodyLimitExceeded = responseBody.limitExceeded,
            )
        } finally {
            connection.disconnect()
        }
    }
}

internal fun buildDriveDestinationMetadataUrl(folderId: String): URL {
    require(folderId.matches(DRIVE_FILE_ID_PATTERN))
    return URL(
        "$DRIVE_FILES_ENDPOINT/$folderId" +
            "?supportsAllDrives=true&fields=$ENCODED_DESTINATION_FIELDS",
    )
}

internal class DriveResponseBody(
    val body: String?,
    val limitExceeded: Boolean,
) {
    override fun toString(): String =
        "DriveResponseBody(body=REDACTED, limitExceeded=$limitExceeded)"

    internal companion object {
        val Empty = DriveResponseBody(body = null, limitExceeded = false)
    }
}

internal fun readDriveResponseBody(
    inputStream: InputStream,
    maxBytes: Int,
): DriveResponseBody {
    require(maxBytes >= 0)
    return inputStream.use { stream ->
        val bytes = ByteArrayOutputStream(minOf(maxBytes, RESPONSE_BUFFER_BYTES))
        val buffer = ByteArray(RESPONSE_BUFFER_BYTES)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            if (count > maxBytes - bytes.size()) {
                return DriveResponseBody(body = null, limitExceeded = true)
            }
            bytes.write(buffer, 0, count)
        }
        val body = try {
            Charsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes.toByteArray()))
                .toString()
        } catch (_: CharacterCodingException) {
            null
        }
        DriveResponseBody(body = body, limitExceeded = false)
    }
}

private val DRIVE_FILE_ID_PATTERN = Regex("^[A-Za-z0-9_-]+$")
private const val DRIVE_FILES_ENDPOINT = "https://www.googleapis.com/drive/v3/files"
private const val ENCODED_DESTINATION_FIELDS =
    "id%2CmimeType%2Ctrashed%2Ccapabilities%28canAddChildren%2CcanListChildren%29"
private const val CONNECT_TIMEOUT_MILLIS = 15_000
private const val READ_TIMEOUT_MILLIS = 30_000
private const val MAX_RESPONSE_BYTES = 32_768
private const val RESPONSE_BUFFER_BYTES = 4_096
