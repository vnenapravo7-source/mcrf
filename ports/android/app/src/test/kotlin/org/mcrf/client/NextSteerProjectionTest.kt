package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NextSteerProjectionTest {
    @Test fun groupStorageDoesNotEnterNativeSettingsOrMutateSavedGroups() {
        val saved=RouteRules.defaults();RoutingGroups.ensure(saved)
        saved.getJSONArray("rules").put(JSONObject().put("id","vpn").put("text","vpn.example"))
        RoutingGroups.select(saved,"bs")
        saved.getJSONArray("rules").put(JSONObject().put("id","bs").put("text","bs.example"))
        saved.put("defaultTarget","direct")
        val before=saved.toString();val runtime=RoutingGroups.runtime(saved)
        assertFalse(runtime.has(RoutingGroups.KEY));assertFalse(runtime.toString().contains("vpn.example"))
        assertEquals("bs.example",runtime.getJSONArray("rules").getJSONObject(0).getString("text"))
        assertEquals("direct",runtime.getString("defaultTarget"));assertEquals(before,saved.toString())
        runtime.getJSONArray("rules").getJSONObject(0).put("text","changed.example")
        assertEquals(before,saved.toString())
    }
    @Test fun onlyTheStorageEnvelopeIsRemovedNotUnsupportedRuntimeSettings() {
        val saved=RouteRules.defaults();RoutingGroups.ensure(saved)
        saved.put("futureNativeOption",JSONObject().put("enabled",true))
        val runtime=RoutingGroups.runtime(saved)
        assertTrue(runtime.getJSONObject("futureNativeOption").getBoolean("enabled"))
        assertTrue(runtime.has("globalDns"));assertTrue(runtime.has("excludedApps"))
        assertFalse(runtime.has(RoutingGroups.KEY))
    }
    @Test fun nativeFailureShowsCodeAndKnownFieldPathWithoutConfigurationValues() {
        val message=SteerCompatibilityHints.describe("UNKNOWN_FIELD","routing.routingGroups")
        assertTrue(message.contains("UNKNOWN_FIELD · routing.routingGroups"))
        assertTrue(message.contains("Причина:"))
    }
    @Test fun untrustedCodeAndPathCannotEchoUrlsOrMultilineValues() {
        val message=SteerCompatibilityHints.describe("bad\nsecret","https://secret.invalid/token")
        assertTrue(message.contains("UNSUPPORTED"));assertFalse(message.contains("secret"));assertFalse(message.contains("https://"))
        assertFalse(SteerCompatibilityHints.describe("UNKNOWN_FIELD","routing."+"a".repeat(200)).contains("a".repeat(200)))
    }
}
