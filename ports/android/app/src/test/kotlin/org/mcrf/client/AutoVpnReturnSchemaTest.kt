package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Queued source only. Real Keystore/generation/restore acceptance remains separate. */
class AutoVpnReturnSchemaTest {
    private fun value()=JSONObject().put("version",1).put("template",JSONObject().put("outbounds",JSONArray()))
        .put("vpn",true).put("routes",true).put("port",10808).put("core","singbox").put("ids",JSONArray().put("a".repeat(64)))
    @Test fun cacheHasARegisteredPortableEncryptedSection(){
        ClientSettingsRegistry.register()
        assertTrue(AutoVpnReturn.KEY in AndroidBackup.sectionKeys())
        assertTrue(BsDetectionMonitor.KEY in AndroidBackup.preferenceKeys())
        assertTrue("logsTerminal" in AndroidBackup.preferenceKeys())
        AndroidBackup.validatePreferences(JSONObject().put(AutoVpnReturn.KEY,value()).put(BsDetectionMonitor.KEY,false).put("logsTerminal",true))
    }
    @Test fun validPlainVpnTargetDoesNotBecomeAWhitelistProfile(){
        AutoVpnReturn.validate(value())
        for(key in listOf("mcrf_bs","mcrf_carrier")){
            val bad=value();bad.getJSONObject("template").put(key,JSONObject())
            assertThrows(IllegalArgumentException::class.java){AutoVpnReturn.validate(bad)}
        }
    }
    @Test fun malformedTypesPortsCoreAndForeignFieldsAreRejected(){
        for((key,item) in listOf("version" to 1.5,"vpn" to "true","routes" to 1,"port" to 0,"core" to "random","unknown" to true)){
            assertThrows(IllegalArgumentException::class.java){AutoVpnReturn.validate(value().put(key,item))}
        }
        assertThrows(IllegalArgumentException::class.java){AutoVpnReturn.validate(value().put("ids",JSONArray().put("not-a-server-id")))}
    }
    @Test fun portableRestoreKeepsPreferencesButCannotActivateEmbeddedRuntimeTarget(){
        ClientSettingsRegistry.register()
        val preferences=JSONObject().put(AutoVpnReturn.KEY,value()).put(BsDetectionMonitor.KEY,true).put("logsTerminal",true)
        val stores=JSONObject().put("profile.enc","").put("connections.enc",ConnectionCatalogue("").serialize())
            .put("routing.enc",RouteRules.defaults().toString()).put("preferences.enc",preferences.toString())
        val restored=AndroidBackup.validate(JSONObject().put("version",1).put("stores",stores))
        val portable=JSONObject(restored.getValue("preferences.enc"))
        assertFalse(portable.has(AutoVpnReturn.KEY))
        assertTrue(portable.getBoolean(BsDetectionMonitor.KEY));assertTrue(portable.getBoolean("logsTerminal"))
        assertTrue(preferences.has(AutoVpnReturn.KEY)) // Inspection never mutates the input.
    }
}
