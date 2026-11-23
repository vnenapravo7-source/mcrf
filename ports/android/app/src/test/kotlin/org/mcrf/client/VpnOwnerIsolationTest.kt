package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class VpnOwnerIsolationTest {
    private val packages=mapOf("org.example.allowed" to 10001,"org.example.shared" to 10001,
        "org.example.other" to 10002,"moe.shizuku.privileged.api" to 10003)

    private fun scope(included:List<String>,excluded:List<String>,strict:Boolean=true,bypassUid:Int?=null) =
        VpnOwnerIsolation.capture(strict,included,excluded,packages::get,
            {uid->packages.filterValues {it==uid}.keys},bypassUid)

    @Test fun alsoDeniesPackagesSharingAnExcludedUid() {
        val captured=scope(emptyList(),listOf("org.example.shared","moe.shizuku.privileged.api"))
        assertFalse(captured.admits(10001,listOf("org.example.allowed","org.example.shared")))
        assertFalse(captured.admits(10003,listOf("moe.shizuku.privileged.api")))
        assertTrue(captured.admits(10002,listOf("org.example.other")))
        assertFalse(captured.admits(-1,emptyList()))
        assertFalse(captured.admits(2000,emptyList()))
        assertFalse(captured.admits(2000,listOf("com.android.shell")))
        assertFalse(captured.admits(0,listOf("org.example.allowed")))
    }

    @Test fun includesOnlyCompleteSharedUids() {
        val captured=scope(listOf("org.example.allowed","org.example.other"),emptyList())
        assertEquals(setOf("org.example.other"),captured.included.keys)
        assertFalse(captured.admits(10001,listOf("org.example.allowed","org.example.shared")))
        assertFalse(captured.admits(10002,listOf("org.example.other","org.example.newAlias")))
        assertTrue(captured.admits(10002,listOf("org.example.other")))
    }

    @Test fun selectedAppsUseVpnWhileDirectOwnersStayOutsideScope() {
        val captured=scope(
            listOf("org.example.allowed","org.example.shared","org.example.other"),
            listOf("org.example.shared")
        )
        assertTrue(captured.includeOnly)
        assertEquals(setOf("org.example.other"),captured.included.keys)
        assertTrue(captured.admits(10002,listOf("org.example.other")))
        assertFalse(captured.admits(10003,listOf("moe.shizuku.privileged.api")))
        assertFalse(captured.admits(10001,listOf("org.example.allowed","org.example.shared")))
        assertFalse(captured.admits(10002,emptyList()))
    }

    @Test fun emptyProjectionNeverOpensCaptureToAllApps() {
        assertThrows(IllegalArgumentException::class.java) {scope(listOf("org.example.uninstalled"),emptyList())}
        assertThrows(IllegalArgumentException::class.java) {
            scope(listOf("org.example.other"),emptyList(),strict=false,bypassUid=10002)
        }
    }

    @Test fun validLegacyOwnerMetadataSurvivesStrictOptOut() {
        val captured=scope(emptyList(),listOf("org.example.shared"),strict=false)
        assertTrue(captured.admits(10001,listOf("org.example.allowed")))
        assertFalse(captured.admits(-1,emptyList()))
    }

    @Test fun packageUpdatesAreTrackedByConfiguredNameOrSharedUid() {
        val captured=scope(emptyList(),listOf("org.example.shared","org.example.uninstalled"))
        assertTrue(captured.tracksPackage("org.example.uninstalled",-1))
        assertTrue(captured.tracksPackage("org.example.newAlias",10001))
        assertFalse(captured.tracksPackage("org.example.newApp",10002))
        assertFalse(captured.admits(10007,listOf("org.example.uninstalled")))
    }
}
