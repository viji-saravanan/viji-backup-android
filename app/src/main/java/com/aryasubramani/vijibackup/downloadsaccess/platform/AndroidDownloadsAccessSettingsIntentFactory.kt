package com.aryasubramani.vijibackup.downloadsaccess.platform

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

class AndroidDownloadsAccessSettingsIntentFactory(
    private val packageName: String,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
    private val canResolve: (Intent) -> Boolean,
) {
    constructor(context: Context) : this(
        packageName = context.packageName,
        canResolve = { intent -> intent.resolveActivity(context.packageManager) != null },
    )

    fun create(): Intent? {
        if (sdkInt < Build.VERSION_CODES.R) return null

        val appSettings = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.fromParts("package", packageName, null),
        )
        if (canResolve(appSettings)) return appSettings

        val globalSettings = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        return globalSettings.takeIf(canResolve)
    }
}
