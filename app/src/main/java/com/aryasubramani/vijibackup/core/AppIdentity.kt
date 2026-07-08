package com.aryasubramani.vijibackup.core

object AppIdentity {
    const val displayName = "Viji Backup"
    const val baseApplicationId = "com.aryasubramani.vijibackup"
    const val internalApplicationId = "com.aryasubramani.vijibackup.internal"
    const val phaseLabel = "Foundation"

    val releaseChannels = setOf("internal", "public")

    fun isSupportedReleaseChannel(channel: String): Boolean =
        channel in releaseChannels
}
