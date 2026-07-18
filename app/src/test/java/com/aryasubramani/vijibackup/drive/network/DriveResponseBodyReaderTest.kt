package com.aryasubramani.vijibackup.drive.network

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveResponseBodyReaderTest {
    @Test
    fun exactByteLimitIsAccepted() {
        val result = readDriveResponseBody(
            inputStream = ByteArrayInputStream("1234".toByteArray()),
            maxBytes = 4,
        )

        assertEquals("1234", result.body)
        assertFalse(result.limitExceeded)
    }

    @Test
    fun oneByteOverLimitIsRejectedWithoutReturningPartialContent() {
        val result = readDriveResponseBody(
            inputStream = ByteArrayInputStream("12345".toByteArray()),
            maxBytes = 4,
        )

        assertNull(result.body)
        assertTrue(result.limitExceeded)
        assertFalse(result.toString().contains("1234"))
    }

    @Test
    fun malformedUtf8IsRejected() {
        val result = readDriveResponseBody(
            inputStream = ByteArrayInputStream(byteArrayOf(0xC3.toByte(), 0x28)),
            maxBytes = 4,
        )

        assertNull(result.body)
        assertFalse(result.limitExceeded)
    }
}
