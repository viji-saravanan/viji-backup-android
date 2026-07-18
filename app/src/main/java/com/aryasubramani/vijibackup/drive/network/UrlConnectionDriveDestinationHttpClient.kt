package com.aryasubramani.vijibackup.drive.network

import com.aryasubramani.vijibackup.drive.google.DriveAccessToken
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext

internal class UrlConnectionDriveDestinationHttpClient(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : DriveDestinationHttpClient {
    override suspend fun getMetadata(
        folderId: String,
        accessToken: DriveAccessToken,
    ): DriveDestinationHttpResponse = withContext(ioDispatcher) {
        val connection = buildDriveDestinationMetadataUrl(folderId)
            .openConnection() as HttpURLConnection
        val cancellationHandle = currentCoroutineContext().job.invokeOnCompletion {
            connection.disconnect()
        }
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
            val body = if (statusCode == HttpURLConnection.HTTP_OK) {
                connection.inputStream?.readBoundedUtf8(MAX_RESPONSE_CHARACTERS)
            } else {
                null
            }
            DriveDestinationHttpResponse(statusCode = statusCode, body = body)
        } finally {
            cancellationHandle.dispose()
            connection.errorStream?.close()
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

private fun InputStream.readBoundedUtf8(maxCharacters: Int): String? =
    reader(Charsets.UTF_8).use { reader ->
        val body = StringBuilder()
        val buffer = CharArray(RESPONSE_BUFFER_CHARACTERS)
        while (true) {
            val count = reader.read(buffer)
            if (count < 0) break
            if (body.length + count > maxCharacters) return null
            body.append(buffer, 0, count)
        }
        body.toString()
    }

private val DRIVE_FILE_ID_PATTERN = Regex("^[A-Za-z0-9_-]+$")
private const val DRIVE_FILES_ENDPOINT = "https://www.googleapis.com/drive/v3/files"
private const val ENCODED_DESTINATION_FIELDS =
    "id%2CmimeType%2Ctrashed%2Ccapabilities%28canAddChildren%2CcanListChildren%29"
private const val CONNECT_TIMEOUT_MILLIS = 15_000
private const val READ_TIMEOUT_MILLIS = 30_000
private const val MAX_RESPONSE_CHARACTERS = 32_768
private const val RESPONSE_BUFFER_CHARACTERS = 4_096
