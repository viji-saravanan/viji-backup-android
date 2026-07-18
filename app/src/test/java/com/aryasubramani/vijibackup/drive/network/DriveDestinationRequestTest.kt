package com.aryasubramani.vijibackup.drive.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveDestinationRequestTest {
    @Test
    fun requestTargetsOnlyTheConfiguredFileWithTheMinimumMetadataFields() {
        val url = buildDriveDestinationMetadataUrl("folder-ID_123")

        assertEquals("https", url.protocol)
        assertEquals("www.googleapis.com", url.host)
        assertEquals("/drive/v3/files/folder-ID_123", url.path)
        assertTrue(url.query.contains("supportsAllDrives=true"))
        assertTrue(
            url.query.contains(
                "fields=id%2CmimeType%2Ctrashed%2Ccapabilities%28canAddChildren%2CcanListChildren%29",
            ),
        )
        assertFalse(url.query.contains("name"))
        assertFalse(url.query.contains("permissions"))
        assertFalse(url.query.contains("owners"))
    }
}
