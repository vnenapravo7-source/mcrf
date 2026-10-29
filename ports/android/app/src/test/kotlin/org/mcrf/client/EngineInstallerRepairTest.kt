package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class EngineInstallerRepairTest {
    private fun inspection(vararg signers:String)=EngineSignerPolicy.Inspection("apk",signers.toSet())
    private fun rejection(actual:Set<String>,trusted:Set<String>):String {
        try { EngineSignerPolicy.requireSame(actual,trusted);fail("Untrusted or unreadable signer accepted") }
        catch(error:IllegalArgumentException) { return error.message.orEmpty() }
        error("Expected rejection")
    }

    @Test fun readableModernSignerIsAuthoritativeEvenWhenLegacyWouldMatch() {
        val result=EngineSignerPolicy.inspect({inspection("untrusted")},{fail("Must not replace a readable modern signer");inspection("trusted")})!!
        assertTrue(rejection(result.signers,setOf("trusted")).contains("не совпадает"))
    }
    @Test fun missingSigningInfoUsesLegacyCertificatesForArchiveAndHost() {
        val archive=EngineSignerPolicy.inspect({inspection()},{inspection("trusted")})!!
        val host=EngineSignerPolicy.inspect({null},{inspection("trusted")})!!
        EngineSignerPolicy.requireSame(archive.signers,host.signers)
    }
    @Test fun vendorModernApiFailureUsesLegacyCertificates() {
        val result=EngineSignerPolicy.inspect({throw UnsupportedOperationException("vendor SigningInfo")},{inspection("trusted")})!!
        assertEquals(setOf("trusted"),result.signers)
    }
    @Test fun fallbackSignerMismatchIsRejected() {
        val result=EngineSignerPolicy.inspect({inspection()},{inspection("other")})!!
        assertTrue(rejection(result.signers,setOf("trusted")).contains("не совпадает"))
    }
    @Test fun missingArchiveAndMissingHostSignersHaveDistinctErrors() {
        val unreadable=EngineSignerPolicy.inspect({inspection()},{inspection()})!!
        assertTrue(rejection(unreadable.signers,setOf("trusted")).contains("подпись APK движка"))
        assertTrue(rejection(setOf("trusted"),unreadable.signers).contains("подпись MCRF"))
        rejection(emptySet(),emptySet())
    }
    @Test fun nullOrFailingFallbackCannotAuthorizeEmptyModernSigners() {
        val absent=EngineSignerPolicy.inspect({inspection()},{null})!!
        val failed=EngineSignerPolicy.inspect({inspection()},{throw IllegalStateException("legacy unavailable")})!!
        rejection(absent.signers,setOf("trusted"));rejection(failed.signers,setOf("trusted"))
        assertNull(EngineSignerPolicy.inspect<String>({null},{null}))
    }
    @Test fun completeSignerSetsMustMatchWithoutSubsetOrHistoryTrust() {
        EngineSignerPolicy.requireSame(setOf("a","b"),setOf("b","a"))
        rejection(setOf("a"),setOf("a","b"))
        rejection(setOf("a","b"),setOf("a"))
        rejection(setOf("old"),setOf("current"))
    }
    @Test fun permissionGrantBeforeDownloadResumesDownloadNotMissingFileInstall() {
        assertEquals("download-ready",EngineInstallPermission.resumePhase(true,false))
    }
    @Test fun permissionRegrantWithVerifiedArchiveResumesInstaller() {
        assertEquals("install-ready",EngineInstallPermission.resumePhase(true,true))
    }
    @Test fun permissionDenialStopsBothDownloadAndInstaller() {
        for(hasArchive in listOf(false,true))assertEquals("permission-denied",EngineInstallPermission.resumePhase(false,hasArchive))
    }
    @Test fun settingsAreRequestedBeforeOfferOnlyOnce() {
        val required=EngineInstallPermission.beforeOfferPhase("download-ready",false)
        assertEquals("permission-required",required)
        assertEquals(required,EngineInstallPermission.beforeOfferPhase(required,false))
        assertEquals("permission",EngineInstallPermission.beforeOfferPhase("permission",false))
        assertEquals("permission-denied",EngineInstallPermission.beforeOfferPhase("permission-denied",false))
    }
    @Test fun grantResumesOfferAndDenialDoesNotReopenSettings() {
        val granted=EngineInstallPermission.resumePhase(true,false)
        assertEquals("download-ready",EngineInstallPermission.beforeOfferPhase(granted,true))
        val denied=EngineInstallPermission.resumePhase(false,false)
        repeat(5){assertEquals(denied,EngineInstallPermission.beforeOfferPhase(denied,false))}
    }
}
