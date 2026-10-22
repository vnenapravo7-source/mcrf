package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class ServerManagementFailureTest {
    @Test fun distinctFailureBoundariesAreTransparentWithoutRawExceptionText(){
        val codes=listOf("SSH_TRUST_REQUIRED","SSH_TRUST_REJECTED","SSH_KEY_CHANGED","SSH_AUTH","SSH_CONNECT","TLS_PIN","PANEL_HTTP_401","PANEL_HTTP_404","STATUS_RESULT","CLIENT_LIST","TIMEOUT","REMOTE_EXEC")
        assertEquals(codes.size,codes.map {ServerManagementFailure(it).message}.distinct().size)
        codes.forEach {code->assertTrue(ServerManagementFailure(code).message!!.endsWith("[$code]"))}
    }
    @Test fun unrecognizedRemoteTextCannotLeakThroughCodeOrMessage(){
        val raw="fixture-secret-password\nBearer fixture-private-token"
        val error=ServerManagementFailure(raw)
        assertEquals("PANEL_RESPONSE_INVALID",error.code)
        assertFalse(error.message!!.contains("fixture-secret"));assertFalse(error.message!!.contains("Bearer"))
        assertFalse(ServerManagementFailure.message(raw).contains("fixture-private-token"))
    }
}
