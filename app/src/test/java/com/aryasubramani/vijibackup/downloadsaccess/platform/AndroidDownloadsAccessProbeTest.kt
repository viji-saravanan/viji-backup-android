package com.aryasubramani.vijibackup.downloadsaccess.platform

import com.aryasubramani.vijibackup.downloadsaccess.domain.DownloadsAccessPlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AndroidDownloadsAccessProbeTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun api29UsesSafAndApi30UsesDedicatedAllFilesAccess() {
        val root = temporaryFolder.newFolder("Download")

        assertEquals(
            DownloadsAccessPlatform.SafPicker,
            createProbe(sdkInt = 29, root = root).platform,
        )
        assertEquals(
            DownloadsAccessPlatform.AllFiles,
            createProbe(sdkInt = 30, root = root).platform,
        )
    }

    @Test
    fun broadAccessResultComesOnlyFromInjectedAndroidCheck() {
        val root = temporaryFolder.newFolder("Download")

        assertFalse(createProbe(accessGranted = false, root = root).hasAccess())
        assertTrue(createProbe(accessGranted = true, root = root).hasAccess())
    }

    @Test
    fun mountedAndMountedReadOnlyStorageAreAvailable() {
        val root = temporaryFolder.newFolder("Download")

        assertTrue(createProbe(storageState = "mounted", root = root).isPrimaryStorageAvailable())
        assertTrue(
            createProbe(storageState = "mounted_ro", root = root)
                .isPrimaryStorageAvailable(),
        )
        assertFalse(
            createProbe(storageState = "unmounted", root = root)
                .isPrimaryStorageAvailable(),
        )
    }

    @Test
    fun downloadsRootMustBeReadableDirectory() {
        val directory = temporaryFolder.newFolder("Download")
        val file = temporaryFolder.newFile("not-a-directory")
        val missing = temporaryFolder.root.resolve("missing")

        assertTrue(createProbe(root = directory).isDownloadsRootReadable())
        assertFalse(createProbe(root = file).isDownloadsRootReadable())
        assertFalse(createProbe(root = missing).isDownloadsRootReadable())
    }
}

private fun createProbe(
    sdkInt: Int = 34,
    accessGranted: Boolean = true,
    storageState: String = "mounted",
    root: java.io.File,
) = AndroidDownloadsAccessProbe(
    sdkInt = sdkInt,
    allFilesAccess = { accessGranted },
    externalStorageState = { storageState },
    downloadsDirectory = { root },
)
