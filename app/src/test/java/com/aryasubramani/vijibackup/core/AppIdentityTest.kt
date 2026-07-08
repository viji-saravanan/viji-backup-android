package com.aryasubramani.vijibackup.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppIdentityTest {
    @Test
    fun appIdentityUsesConfirmedNameAndPackage() {
        assertEquals("Viji Backup", AppIdentity.displayName)
        assertEquals("com.aryasubramani.vijibackup", AppIdentity.baseApplicationId)
    }

    @Test
    fun releaseChannelsOnlyAllowKnownDistributionSurfaces() {
        assertTrue(AppIdentity.isSupportedReleaseChannel("internal"))
        assertTrue(AppIdentity.isSupportedReleaseChannel("public"))
        assertFalse(AppIdentity.isSupportedReleaseChannel(""))
        assertFalse(AppIdentity.isSupportedReleaseChannel("debug"))
        assertFalse(AppIdentity.isSupportedReleaseChannel("private"))
    }
}
