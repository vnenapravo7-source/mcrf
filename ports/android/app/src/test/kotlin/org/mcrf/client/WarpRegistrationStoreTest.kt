package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class WarpRegistrationStoreTest {
    private fun account()=JSONObject().put("id","fixture").put("token","fixture-only")
        .put("private_key",Base64.getEncoder().encodeToString(ByteArray(32){(it+1).toByte()}))
        .put("peer_public_key",WarpProfile.PEER_KEY).put("ipv4","172.16.0.2").toString()
    @Test fun pendingAccountRetainsCredentialsWithoutCreatingAProfile() {
        val result=WarpRegistrationStore.validate(JSONObject().put("account",account()))
        assertEquals(setOf("account"),result.keys().asSequence().toSet())
        assertEquals("fixture-only",JSONObject(result.getString("account")).getString("token"))
    }
    @Test fun malformedOrUnknownPendingBackupDataRejects() {
        assertThrows(Exception::class.java) {WarpRegistrationStore.validate(JSONObject().put("account",account()).put("config","unverified"))}
        assertThrows(Exception::class.java) {WarpRegistrationStore.validate(JSONObject().put("account",JSONObject(account()).put("token","").toString()))}
        assertThrows(Exception::class.java) {WarpRegistrationStore.validate(JSONObject().put("account",JSONObject(account()).put("private_key","invalid").toString()))}
    }
}
