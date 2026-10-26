package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class PrimaryValidationDiagnosticTest {
    @Test fun nativeTechnicalRejectionSurvivesBothIpcChecks(){
        val sent=PrimaryValidationDiagnostic.reject("DNS_PROTOCOL","settings")
        val received=PrimaryValidationDiagnostic.reject(sent.code,sent.field)
        assertEquals("DNS_PROTOCOL",received.code);assertEquals("settings",received.field)
    }
    @Test fun nativeMessagesUrlsAndCredentialsDoNotCrossBoundary(){
        val result=PrimaryValidationDiagnostic.reject("https://owned:private@unit.invalid/profile","profile.password")
        assertEquals("PRIMARY_CORE_CONFIG_REJECTED",result.code);assertEquals("config",result.field)
        assertFalse(result.toString().contains("private"));assertFalse(result.toString().contains("unit.invalid"))
    }
    @Test fun missingOversizedAndUnknownLocationsUseFixedFallback(){
        assertEquals(PrimaryValidationDiagnostic.Rejection("PRIMARY_CORE_CONFIG_REJECTED","config"),PrimaryValidationDiagnostic.reject(null,null))
        assertEquals(PrimaryValidationDiagnostic.Rejection("PRIMARY_CORE_CONFIG_REJECTED","config"),PrimaryValidationDiagnostic.reject("A".repeat(65),"saved/path"))
        assertEquals("NATIVE_LOAD",PrimaryValidationDiagnostic.reject("NATIVE_LOAD","engine").code)
    }
}
