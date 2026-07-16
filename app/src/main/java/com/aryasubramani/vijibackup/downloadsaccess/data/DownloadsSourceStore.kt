package com.aryasubramani.vijibackup.downloadsaccess.data

data class DownloadsSourceConfiguration(
    val configured: Boolean = false,
    val enabled: Boolean = false,
)

interface DownloadsSourceStore {
    suspend fun read(): DownloadsSourceConfiguration

    suspend fun write(configuration: DownloadsSourceConfiguration)
}
