package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CsqttRoutingPrecedenceTest {
    private fun local()=RouteRules.defaults()
    @Test fun emptyAndBaseInstallationUseServer() {
        assertTrue(CsqttRoutingPrecedence.usesServer(local()))
        for(base in listOf(RouteRules.russiaDirect("example.invalid"),RouteRules.outsideRussia("example.invalid"))) {
            assertTrue(CsqttRoutingPrecedence.usesServer(local().put("rules",JSONArray().put(base)),"example.invalid"))
        }
    }
    @Test fun enabledClientRuleTakesPriorityEvenAlongsideBase() {
        val settings=local().put("rules",JSONArray().put(RouteRules.russiaDirect("example.invalid"))
            .put(JSONObject().put("enabled",true).put("mode","addresses").put("target","block").put("text","private.invalid")))
        val before=settings.toString()
        assertFalse(CsqttRoutingPrecedence.usesServer(settings))
        assertEquals(before,settings.toString())
        settings.getJSONArray("rules").getJSONObject(1).put("enabled",false)
        assertTrue(CsqttRoutingPrecedence.usesServer(settings,"example.invalid"))
    }
    @Test fun appSelectionAndChangedDefaultTakePriority() {
        assertFalse(CsqttRoutingPrecedence.usesServer(local().put("excludedApps",JSONArray().put("org.example.app"))))
        assertFalse(CsqttRoutingPrecedence.usesServer(local().put("vpnApps",JSONArray().put("org.example.app"))))
        assertFalse(CsqttRoutingPrecedence.usesServer(local().put("appMode","include")))
        assertFalse(CsqttRoutingPrecedence.usesServer(local().put("defaultTarget","direct")))
    }
    @Test fun customizedCountryRuleIsClientPolicy() {
        val rule=RouteRules.russiaDirect("example.invalid").put("target","block")
        assertFalse(CsqttRoutingPrecedence.usesServer(local().put("rules",JSONArray().put(rule))))
        rule.put("target","direct").put("dns",JSONObject())
        assertFalse(CsqttRoutingPrecedence.usesServer(local().put("rules",JSONArray().put(rule))))
    }
    @Test fun changedCountryContentsRemainClientPolicyEvenWithBuiltinMarker() {
        assertFalse(CsqttRoutingPrecedence.usesServer(local().put("rules",JSONArray().put(RouteRules.russiaDirect("custom.invalid"))),"example.invalid"))
    }
    @Test fun generatedLoopbackGuardDoesNotCountAsClientRouting() {
        val source=JSONObject().put("outbounds",JSONArray()
            .put(JSONObject().put("type","direct").put("tag","mcrf-loopback").put("inet4_bind_address","127.0.0.1").put("inet6_bind_address","::1")))
            .put("route",JSONObject().put("final","proxy").put("rules",JSONArray()
                .put(JSONObject().put("ip_cidr",JSONArray().put("127.0.0.0/8").put("::1/128")).put("action","route").put("outbound","mcrf-loopback"))
                .put(JSONObject().put("action","sniff"))))
        assertFalse(CsqttRoutingPrecedence.hasSourceRules(source))
        source.getJSONObject("route").getJSONArray("rules").put(JSONObject().put("domain_suffix","custom.invalid").put("outbound","direct"))
        assertTrue(CsqttRoutingPrecedence.hasSourceRules(source))
    }
}
