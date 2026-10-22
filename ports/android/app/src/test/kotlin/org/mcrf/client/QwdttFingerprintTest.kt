package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class QwdttFingerprintTest {
    private fun profile()=JSONObject().put("type","qwdtt").put("server","192.0.2.1").put("server_port",56000)
        .put("password","fixture-password").put("hashes",JSONArray().put("A".repeat(43)))
    @Test fun browserFamilyReachesPrivateConfigAndShareWithoutChangingTheServerIdentity() {
        val old=SwgProfile.fromJson("same-original",profile())
        assertEquals("chrome",old.carrier!!.getString("fingerprint"))
        for(kind in listOf("chrome","firefox","safari")) {
            val changed=SwgProfile.fromJson("same-original",profile().put("fingerprint",kind))
            assertEquals(old.id,changed.id)
            val imported=ServerImport.read(SwgProfile.share(changed.carrier!!)).single().carrier!!
            assertEquals(kind,SwgProfile.privateConfig(imported,22001,22002,"user","pass","device").getString("fingerprint"))
        }
        assertThrows(IllegalArgumentException::class.java){SwgProfile.fromJson("fixture",profile().put("fingerprint","android"))}
    }
}
