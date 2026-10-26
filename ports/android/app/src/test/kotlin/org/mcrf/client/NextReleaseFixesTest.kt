package org.mcrf.client

import java.net.URI
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NextReleaseFixesTest {
    @Test fun skippedReleaseNotesAreCompleteOrderedAndAndroidOnly() {
        fun row(tag:String,body:String,draft:Boolean=false)=JSONObject().put("tag_name",tag).put("body",body).put("draft",draft)
        val found=AppRelease(EnginePackage("arm64-v8a","android-v0.8",URI("https://github.com/fixture"),1,"a".repeat(64)),false,"Latest")
        val data=JSONArray().put(row("android-v0.7","Middle\n"+"detail ".repeat(100)))
            .put(row("v9.0","Windows")).put(row("android-v0.6.1","Installed"))
            .put(row("android-v0.9","Future")).put(row("android-v0.8","Latest"))
            .put(row("android-v0.7.1","Draft",true)).put(row("android-v0.7","Duplicate"))
        val notes=AppRelease.withHistory(found,data,"0.6.1").notes
        assertTrue(notes.startsWith("v0.8\nLatest\n\nv0.7\nMiddle"))
        assertTrue(notes.length>600)
        for(excluded in listOf("Windows","Installed","Future","Draft","Duplicate"))assertFalse(notes.contains(excluded))
    }
    @Test fun newQwdttDefaultsToRawEvenWithManualCallsButSavedAndExplicitModesRemain() {
        val original=JSONObject().put("type","qwdtt").put("server","192.0.2.1").put("server_port",56000)
            .put("password","owned-fixture").put("hashes",JSONArray().put("A".repeat(32)))
        val before=original.toString()
        val imported=JSONObject(CompatibilityImportQwdtt.applyNew(before){error("Manual calls must not generate VK calls")})
        assertEquals("raw",imported.getString("mode"));assertEquals(before,original.toString())
        assertEquals("wg",SwgProfile.fromJson(before,original).carrier!!.getString("mode"))
        original.put("mode","wg")
        assertEquals("wg",JSONObject(CompatibilityImportQwdtt.applyNew(original.toString()){it}).getString("mode"))
    }
    @Test fun wakeSignalsCoalesceWithoutResurrectingManualStop() {
        val gate=WakeTunnelCheck()
        assertFalse(gate.admit(0,true,false));assertFalse(gate.admit(1,false,true))
        assertTrue(gate.admit(2,true,true));gate.complete(2,true);assertFalse(gate.admit(5000,true,true))
        assertTrue(gate.admit(15002,true,true));gate.complete(15002,true);assertFalse(gate.admit(40000,true,false))
        assertTrue(gate.admit(40001,true,true))
    }
    @Test fun wakeProbeFailureUsesSecondEndpointBeforeDecidingToReconnect() {
        val seen=mutableListOf<String>()
        val outcome=WakeTunnelCheck.verify(listOf("first","second"),{true}) {url->seen.add(url);if(url=="first")error("owned failure")else true}
        assertEquals(listOf("first","second"),seen)
        assertEquals(WakeTunnelCheck.Result.HEALTHY,outcome)
    }
    @Test fun manualStopDuringWakeProbeCancelsRecoveryAndSecondRequest() {
        var active=true;var calls=0
        val outcome=WakeTunnelCheck.verify(listOf("first","second"),{active}) {calls++;active=false;false}
        assertEquals(1,calls);assertEquals(WakeTunnelCheck.Result.CANCELLED,outcome)
    }
    @Test fun wakeRecoveryIsRequiredOnlyAfterBothEndpointsFailForCurrentSession() {
        var calls=0
        assertEquals(WakeTunnelCheck.Result.FAILED,WakeTunnelCheck.verify(listOf("first","second"),{true}) {calls++;false})
        assertEquals(2,calls)
        assertEquals(WakeTunnelCheck.Result.CANCELLED,WakeTunnelCheck.verify(listOf("first"),{false}) {error("must not probe stopped session")})
    }
    @Test fun rawDeploymentDefaultsReserveItsPortAcrossAllProtocolChoices() {
        val drafts=AndroidDeploySwg.drafts(JSONObject())
        for(i in 0 until drafts.length()) {
            val row=drafts.getJSONObject(i)
            assertNotEquals("56003",row.getString("relay_port"))
            assertNotEquals("56003",row.getString("wg_port"))
        }
    }
    @Test fun newSubscriptionRawDefaultKeepsProviderIdentityAndDoesNotMigrateExistingServer() {
        val link="qwdtt://config?peer=192.0.2.1%3A56000&pass=owned-fixture&hashes="+"A".repeat(32)
        val row=ServerImport.read(link).single()
        fun plan()=ImportPlan.prepare("https://example.invalid/owned-sub") {
            SubscriptionClient.Downloaded(listOf(row),JSONObject())
        }
        val fresh=plan().withNewQwdttDefaults(emptySet()).resolveCsqtt(emptyMap()).entries.single()
        assertEquals(row.id,fresh.id);assertEquals(link,fresh.original)
        assertEquals("raw",fresh.carrier!!.getString("mode"))
        val existing=plan().withNewQwdttDefaults(setOf(row.id)).entries.single()
        assertEquals("wg",existing.carrier!!.getString("mode"))
    }
    @Test fun disabledCountryRuleDoesNotEnterCompiledTrafficOrDnsPolicy() {
        fun source()=JSONObject("""{"inbounds":[],"outbounds":[{"type":"direct","tag":"direct"},{"type":"socks","tag":"proxy","server":"127.0.0.1","server_port":12345}],"route":{"rules":[],"final":"proxy"}}""")
        val state=RouteRules.defaults()
        val rule=RouteRules.outsideRussia("ru\nyandex.ru\n77.88.0.0/18").put("enabled",false)
        state.getJSONArray("rules").put(rule)
        val off=RouteRules.apply(source(),state,true).getJSONObject("route").getJSONArray("rules")
        assertFalse(off.toString().contains("77.88."));assertFalse(off.toString().contains("yandex.ru"))
        rule.put("enabled",true)
        val on=RouteRules.apply(source(),state,true).getJSONObject("route").getJSONArray("rules")
        assertTrue(on.toString().contains("77.88."));assertTrue(on.toString().contains("yandex.ru"))
        assertTrue(on.length()>off.length())
    }
}
