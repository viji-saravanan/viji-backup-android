package com.aryasubramani.vijibackup.downloadsaccess.platform

import android.os.Build
import android.os.Environment
import com.aryasubramani.vijibackup.downloadsaccess.domain.DownloadsAccessPlatform
import com.aryasubramani.vijibackup.downloadsaccess.domain.DownloadsAccessProbe
import java.io.File

class AndroidDownloadsAccessProbe internal constructor(
    private val sdkInt: Int,
    private val allFilesAccess: () -> Boolean,
    private val externalStorageState: () -> String,
    private val downloadsDirectory: () -> File,
) : DownloadsAccessProbe {
    @Suppress("DEPRECATION")
    constructor() : this(
        sdkInt = Build.VERSION.SDK_INT,
        allFilesAccess = {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                Environment.isExternalStorageManager()
        },
        externalStorageState = Environment::getExternalStorageState,
        downloadsDirectory = {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        },
    )

    override val platform: DownloadsAccessPlatform = if (sdkInt >= Build.VERSION_CODES.R) {
        DownloadsAccessPlatform.AllFiles
    } else {
        DownloadsAccessPlatform.SafPicker
    }

    override fun hasAccess(): Boolean =
        platform == DownloadsAccessPlatform.AllFiles && allFilesAccess()

    override fun isPrimaryStorageAvailable(): Boolean = externalStorageState().let { state ->
        state == Environment.MEDIA_MOUNTED || state == Environment.MEDIA_MOUNTED_READ_ONLY
    }

    override fun isDownloadsRootReadable(): Boolean =
        downloadsDirectory().let { root -> root.isDirectory && root.canRead() }
}
