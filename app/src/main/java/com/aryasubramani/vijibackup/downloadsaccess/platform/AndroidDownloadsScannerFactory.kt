package com.aryasubramani.vijibackup.downloadsaccess.platform

import android.os.Environment
import com.aryasubramani.vijibackup.downloadsaccess.domain.DownloadsAccessProbe
import com.aryasubramani.vijibackup.downloadsaccess.domain.DownloadsScanner

@Suppress("DEPRECATION")
internal fun createAndroidDownloadsScanner(
    accessProbe: DownloadsAccessProbe,
): DownloadsScanner = IterativeDownloadsScanner(
    source = FileDownloadsTreeSource(
        rootDirectory = {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        },
        hasAccess = accessProbe::hasAccess,
    ),
)
