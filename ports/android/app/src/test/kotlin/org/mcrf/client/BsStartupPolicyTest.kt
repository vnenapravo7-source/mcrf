package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test
import org.mcrf.client.DirectAccessCheck.Outcome.*
import java.io.File
import java.io.IOException

/** Whole P046 linked batch; policy callbacks perform no real network/native IO. */
class BsStartupPolicyTest {
    private val blocked=DirectAccessCheck.Result(NO_RESPONSE,NO_RESPONSE)
    @Test fun blockedNetworkStartsOnlyBsAndNeverProvisionalVpn(){
        val calls=mutableListOf<String>()
        val result=BsStartupPolicy.start({true},false,false,{false},{calls+="probe";blocked},
            {calls+="vpn"},{calls+="save-original-vpn";calls+="bs"})
        assertEquals(BsStartupPolicy.Decision.BS,result)
        assertEquals(listOf("probe","save-original-vpn","bs"),calls)
    }
    @Test fun eitherResponseStartsVpnWithoutAnyBsAttempt(){
        for(result in listOf(DirectAccessCheck.Result(RESPONSE,RESPONSE),DirectAccessCheck.Result(RESPONSE,NO_RESPONSE),DirectAccessCheck.Result(NO_RESPONSE,RESPONSE),
            DirectAccessCheck.Result(RESPONSE,RESPONSE,NO_RESPONSE))){
            val calls=mutableListOf<String>()
            assertEquals(BsStartupPolicy.Decision.VPN,BsStartupPolicy.start({true},false,false,{false},{calls+="probe";result},{calls+="vpn"},{calls+="bs"}))
            assertEquals(listOf("probe","vpn"),calls) // YouTube outbound cannot be checked before core startup.
        }
    }
    @Test fun disabledExplicitBsAndPrivateFixturesNeverProbe(){
        for((enabled,fixture,alreadyBs) in listOf(Triple(false,false,false),Triple(true,true,false),Triple(true,false,true))){
            val calls=mutableListOf<String>()
            assertFalse(BsStartupPolicy.shouldCheck(enabled,fixture,alreadyBs))
            assertEquals(BsStartupPolicy.Decision.VPN,BsStartupPolicy.start({enabled},fixture,alreadyBs,{false},{calls+="probe";blocked},{calls+="original-path"},{calls+="bs"}))
            assertEquals(listOf("original-path"),calls)
        }
    }
    @Test fun cancellationBeforeOrDuringCheckStartsNothing(){
        val calls=mutableListOf<String>();var cancelled=true
        assertEquals(BsStartupPolicy.Decision.CANCELLED,BsStartupPolicy.start({true},false,false,{cancelled},{calls+="probe";blocked},{calls+="vpn"},{calls+="bs"}))
        assertTrue(calls.isEmpty());cancelled=false
        assertEquals(BsStartupPolicy.Decision.CANCELLED,BsStartupPolicy.start({true},false,false,{cancelled},{calls+="probe";cancelled=true;blocked},{calls+="vpn"},{calls+="bs"}))
        assertEquals(listOf("probe"),calls)
        assertEquals(BsStartupPolicy.Decision.CANCELLED,BsStartupPolicy.choose(DirectAccessCheck.Result(CANCELLED,CANCELLED),true,false))
    }
    @Test fun disablingOptionWhileCheckingKeepsOriginalVpnPath(){
        var enabled=true;val calls=mutableListOf<String>()
        assertEquals(BsStartupPolicy.Decision.VPN,BsStartupPolicy.start({enabled},false,false,{false},{calls+="probe";enabled=false;blocked},{calls+="vpn"},{calls+="bs"}))
        assertEquals(listOf("probe","vpn"),calls)
    }
    @Test fun offlineRestrictedOrPartialEvidenceDoesNotInventBs(){
        for(value in listOf(NO_NETWORK,RESTRICTED))for(other in listOf(RESPONSE,NO_RESPONSE,value)){
            assertEquals(BsStartupPolicy.Decision.VPN,BsStartupPolicy.choose(DirectAccessCheck.Result(value,other),true,false))
            assertEquals(BsStartupPolicy.Decision.VPN,BsStartupPolicy.choose(DirectAccessCheck.Result(other,value),true,false))
        }
    }
    @Test fun failedProbeOrUnavailableBsNeverFallsThroughToProvisionalVpn(){
        val calls=mutableListOf<String>()
        assertThrows(IOException::class.java){BsStartupPolicy.start({true},false,false,{false},{throw IOException("owned-refusal")},{calls+="vpn"},{calls+="bs"})}
        assertTrue(calls.isEmpty())
        assertThrows(IOException::class.java){BsStartupPolicy.start({true},false,false,{false},{blocked},{calls+="vpn"},{calls+="bs";throw IOException("owned-bs-refusal")})}
        assertEquals(listOf("bs"),calls)
    }
    @Test fun startupWiringPreservesMinuteMonitorReturnTargetAndConfirmedNotice(){
        fun source(name:String)=listOf(File("src/main/kotlin/org/mcrf/client/$name.kt"),File("app/src/main/kotlin/org/mcrf/client/$name.kt")).first{it.isFile}.readText()
        assertEquals(60_000L,BsStartupPolicy.MONITOR_INTERVAL_MS)
        val monitor=source("BsDetectionMonitor")
        assertTrue(monitor.contains("main.postDelayed(this,interval())"))
        assertTrue(monitor.contains("main.postDelayed(tick,interval())"))
        assertTrue(monitor.contains("key==BsDetectionInterval.KEY"))
        assertTrue(monitor.contains("result?.shouldSwitch(true,true,stamp.vpnSection)"))
        assertTrue(monitor.contains("result?.shouldReturn(true,stamp.vpnSection)"))
        val service=source("McrfVpnService")
        assertTrue(service.contains("if(bs==null) startVpnWithBsPreflight(source,vpn,port,applyRoutes,requested)"))
        val preflight=service.substringAfter("// P046_BEGIN:").substringBefore("// P046_END")
        assertTrue(preflight.contains("BsStartupPolicy.start("))
        assertTrue(preflight.indexOf("rememberVpnSession(original)")<preflight.indexOf("val chosen=chooseEngine("))
        assertTrue(preflight.contains("RuntimeStart(JSONObject(source.toString()),null,vpn,port,applyRoutes,requested)"))
        assertTrue(preflight.indexOf("val chosen=chooseEngine(")<preflight.indexOf("AutoSectionNotifications.post(this,true)"))
        assertTrue(service.contains("if(stamp.vpnSection)switchAutomaticallyToBs(stamp)else switchAutomaticallyToVpn(stamp)"))
        assertTrue(preflight.contains("catalogue.bsStartupEntries()"))
        assertTrue(preflight.contains("NativeCarrier.executable("))
        assertFalse(preflight.contains("ProfileStore(this).write("))
        assertFalse(preflight.contains("putBoolean(\"automatic\""))
    }
}
