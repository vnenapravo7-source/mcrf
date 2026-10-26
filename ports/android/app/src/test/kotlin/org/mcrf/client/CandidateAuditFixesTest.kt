package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CancellationException

/** NEW audit regressions only. Previously accepted suites are not selected. */
class CandidateAuditFixesTest {
    private fun configured(address:String="10.89.0.2/32",ipv6:Boolean=false):JSONObject {
        val source=ProfileConfig.build("""{"outbounds":[{"type":"direct","tag":"proxy"}],"route":{"final":"proxy"}}""",true,10808)
        val settings=ClientTunSettings.fromJson(JSONObject().put("mtu",1500).put("address4",address).put("ipv6",ipv6).put("mixedEnabled",false))
        val auth=JSONObject().put("enabled",false).put("username","owned").put("password","owned")
        return ClientTunConfig.apply(source,settings,auth,10808).config
    }
    private fun tun(config:JSONObject)=config.getJSONArray("inbounds").getJSONObject(0)
    @Test fun ordinarySingboxHasSafeDnsAfterSteerSettingsWithoutDispatcher(){
        val config=configured();assertEquals("10.89.0.1",tun(config).getJSONArray("dns_address").getString(0))
        assertEquals("10.89.0.2/32",tun(config).getJSONArray("address").getString(0))
        assertEquals("proxy",config.getJSONObject("route").getString("final"))
    }
    @Test fun otherHostOnlyPrefixesAndLastSubnetHostAlsoGetDns(){
        assertEquals("10.42.0.8",tun(configured("10.42.0.9/32")).getJSONArray("dns_address").getString(0))
        assertEquals("10.42.0.0",tun(configured("10.42.0.1/31")).getJSONArray("dns_address").getString(0))
    }
    @Test fun usableNextHostKeepsUpstreamDefaultAndDualStackKeepsBothAddresses(){
        assertFalse(tun(configured("172.19.0.1/30")).has("dns_address"))
        val dual=tun(configured(ipv6=true));assertEquals(2,dual.getJSONArray("address").length())
        assertEquals("fdfe:dcba:9876::1/126",dual.getJSONArray("address").getString(1))
    }
    @Test fun emptyExplicitDnsIsRepairedButNonemptyAndSourceStayIntact(){
        val input=tun(configured());input.put("dns_address",JSONArray())
        val original=JSONObject().put("inbounds",JSONArray().put(input));val before=original.toString()
        assertEquals("10.89.0.1",tun(NativeRouteTunDns.apply(original)).getJSONArray("dns_address").getString(0))
        assertEquals(before,original.toString())
        input.put("dns_address",JSONArray().put("192.0.2.53"))
        assertEquals(original.toString(),NativeRouteTunDns.apply(original).toString())
    }
    @Test fun screenOffSkippedWakeDoesNotSuppressNextWake(){
        val gate=WakeTunnelCheck();assertTrue(gate.admit(100000,true,true));gate.complete(102000,false)
        assertTrue(gate.admit(105000,true,true));assertFalse(gate.admit(105100,true,true))
    }
    @Test fun completedCheckStillCoalescesUserPresentButPendingCannotDuplicate(){
        val gate=WakeTunnelCheck();assertTrue(gate.admit(0,true,true));assertFalse(gate.admit(16000,true,true))
        gate.complete(17000,true);assertFalse(gate.admit(18000,true,true));assertTrue(gate.admit(32000,true,true))
    }
    @Test fun cancelledOwnerAndRejectedWorkerPermitNextRealWake(){
        val gate=WakeTunnelCheck();assertFalse(gate.admit(1,true,false));assertFalse(gate.admit(2,false,true))
        assertTrue(gate.admit(3,true,true));gate.complete(4,false);assertTrue(gate.admit(5,true,true))
        var active=true
        val result=WakeTunnelCheck.verify(listOf("first","second"),{active}){active=false;false}
        assertEquals(WakeTunnelCheck.Result.CANCELLED,result);gate.complete(6,result!=WakeTunnelCheck.Result.CANCELLED)
        assertTrue(gate.admit(7,true,true))
    }
    @Test fun monotonicResetDoesNotFreezeCompletedWakeGate(){
        val gate=WakeTunnelCheck();assertTrue(gate.admit(50000,true,true));gate.complete(51000,true)
        assertTrue(gate.admit(100,true,true))
    }
    private fun release(version:String,name:String="mcrf-android-universal-release.apk"):JSONObject {
        val tag="android-v$version"
        return JSONObject().put("tag_name",tag).put("draft",false).put("body","Changes $version")
            .put("assets",JSONArray().put(JSONObject().put("name",name).put("state","uploaded").put("size",1)
                .put("digest","sha256:"+"0".repeat(64)).put("browser_download_url","https://github.com/${EnginePackage.REPOSITORY}/releases/download/$tag/$name")))
    }
    @Test fun olderBackportFirstCannotHideHigherSemanticVersion(){
        val releases=JSONArray().put(release("0.6.2")).put(release("0.7")).put(release("0.6.1"))
        assertEquals("android-v0.7",AppRelease.find(releases,"arm64-v8a")!!.asset.version)
    }
    @Test fun stableVersionBeatsPrereleaseAndMalformedOrWindowsTagsAreIgnored(){
        val releases=JSONArray().put(release("0.8-rc.1")).put(JSONObject().put("tag_name","v1.99"))
            .put(release("latest")).put(JSONObject.NULL).put(release("0.8"))
        assertEquals("android-v0.8",AppRelease.find(releases,"arm64-v8a")!!.asset.version)
    }
    @Test fun draftAndMissingAndroidAssetDoNotOverrideValidAvailableRelease(){
        val releases=JSONArray().put(release("0.9").put("draft",true)).put(release("0.8","windows.zip")).put(release("0.7"))
        assertEquals("android-v0.7",AppRelease.find(releases,"arm64-v8a")!!.asset.version)
    }
    @Test fun newestAvailableAssetStillRequiresDigestAndExactTrustedOrigin(){
        val releases=JSONArray().put(release("0.6.2")).put(release("0.7"))
        releases.getJSONObject(1).getJSONArray("assets").getJSONObject(0).put("digest","")
        try{AppRelease.find(releases,"arm64-v8a");fail("Invalid highest release must not bypass metadata admission")}catch(_:IllegalArgumentException){}
    }
    @Test fun fullPageWithOlderTagsStillRequiresNextPageAndHistoryIsSorted(){
        assertFalse(AppReleasePagination.complete(100));assertTrue(AppReleasePagination.complete(99));assertTrue(AppReleasePagination.complete(0))
        val releases=JSONArray().put(release("0.6.2")).put(release("0.7")).put(release("0.6.1"))
        val found=AppRelease.withHistory(AppRelease.find(releases,"arm64-v8a")!!,releases,"0.6.1")
        assertTrue(found.notes.indexOf("v0.7")<found.notes.indexOf("v0.6.2"));assertFalse(found.notes.contains("Changes 0.6.1"))
    }
    @Test fun tlsCauseSurvivesThrownDnsHealthErrorAndSecretsAreRedacted(){
        val original=IOException("DNS failed https://owned:fixture-secret@unit.invalid/?token=fixture-secret")
        try{ProtocolHealthCheck.verify({throw original}){IOException("PROTOCOL_CRONET_113: TLS handshake failed")};fail("Must fail")}
        catch(error:IOException){assertTrue(error.message!!.contains("PROTOCOL_CRONET_113"));assertTrue(error.message!!.contains("DNS failed"));assertFalse(error.message!!.contains("fixture-secret"));assertSame(original,error.cause)}
    }
    @Test fun trustFailureCannotBeDowngradedByOuterIoFailure(){
        try{ProtocolHealthCheck.verify({throw IOException("DNS failed")}){SecurityException("PROTOCOL_TLS_DENIED")};fail("Must deny")}
        catch(error:SecurityException){assertTrue(error.message!!.contains("PROTOCOL_TLS_DENIED"));assertFalse(PrimaryFallbackPolicy.canRetryFailure(error))}
        try{ProtocolHealthCheck.verify({throw SecurityException("Network permission denied")}){IOException("Transport details")};fail("Must deny")}
        catch(error:SecurityException){assertFalse(PrimaryFallbackPolicy.canRetryFailure(error))}
    }
    @Test fun actualCancellationAndInterruptAreNotTransportErrors(){
        val cancellation=CancellationException("owned cancellation");val interrupt=InterruptedException("owned interrupt")
        for(original in listOf(cancellation,interrupt)){
            try{ProtocolHealthCheck.verify({throw original}){error("Do not decorate cancellation")};fail("Must cancel")}
            catch(error:Exception){assertSame(original,error)}
        }
    }
    @Test fun falseProbeRetainsNativeFailureButSuccessDoesNotFetchDiagnostics(){
        val native=IOException("PROTOCOL_CONNECTION_REFUSED")
        try{ProtocolHealthCheck.verify({false}){native};fail("Must fail")}catch(error:IOException){assertSame(native,error)}
        assertTrue(ProtocolHealthCheck.verify({true}){error("No failure details on success")})
    }
}
