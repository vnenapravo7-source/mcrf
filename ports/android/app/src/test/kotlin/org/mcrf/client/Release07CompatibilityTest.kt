package org.mcrf.client

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Source/API-guard regressions, not Android10 or live VPN acceptance. */
class Release07CompatibilityTest {
    private fun source(name:String)=File("src/main/kotlin/org/mcrf/client/$name.kt").readText()
    @Test fun wakeOnAndroid10UsesOnlyCapturedOwnedHandle(){val s=source("McrfVpnService");assertTrue(s.contains("if(android.os.Build.VERSION.SDK_INT>=30)caps.ownerUid"));assertTrue(s.contains("else network==(steerVpnNetwork?:packetVpnNetwork)"))}
    @Test fun packetHandleIsCapturedAfterOwnedStartupAndRetired(){val s=source("McrfVpnService");assertTrue(s.contains("packetVpnNetwork=network"));assertTrue(s.contains("packetVpnNetwork=null"));assertTrue(s.indexOf("server.startPacket(original,descriptor,dial")<s.indexOf("packetVpnNetwork=network"))}
    @Test fun olderStartupOwnerFallbackRetainsFullAddressPlan(){val s=source("McrfVpnService");assertEquals(2,Regex("VERSION.SDK_INT<30\\|\\|caps.ownerUid").findAll(s).count());assertTrue(s.contains("actual.size==plan.addresses.size"));assertTrue(s.contains("it.prefixLength==parts[1].toInt()"));assertTrue(s.contains("it.address.hostAddress==\"10.89.0.2\""))}
    @Test fun managedDnsRevalidatesOriginalPlanAndGenerationOnOldApi(){val s=source("PacketManagedDnsRuntime");assertTrue(s.contains("if(android.os.Build.VERSION.SDK_INT>=30)caps.ownerUid"));assertTrue(s.contains("actual.size==original.addresses.size"));assertTrue(s.contains("original.addresses.all"));assertTrue(s.contains("closed.get()||stopped()"));assertTrue(s.contains("caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)&&owned"))}
    @Test fun quickTileKeepsCorrectApiSplit(){val s=source("McrfQuickTiles");assertTrue(s.contains("if(Build.VERSION.SDK_INT>=34)startActivityAndCollapse(PendingIntent.getActivity"));assertTrue(s.contains("else @Suppress(\"DEPRECATION\") startActivityAndCollapse(intent)"))}
    @Test fun logBreakStrategyUsesLineBreakerConstant(){assertTrue(source("LogsPage").contains("breakStrategy=android.graphics.text.LineBreaker.BREAK_STRATEGY_SIMPLE"))}
    @Test fun cacheBypassPreservesBothDnsFlags(){for(name in listOf("McrfVpnService","PacketManagedDnsFixture")){val s=source(name);assertTrue(s.contains("FLAG_NO_CACHE_LOOKUP or"));assertTrue(s.contains("FLAG_NO_CACHE_STORE"));assertTrue(s.contains("noCacheFlags,"))}}
}
