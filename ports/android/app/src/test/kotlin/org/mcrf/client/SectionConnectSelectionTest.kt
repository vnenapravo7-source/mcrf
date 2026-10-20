package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SectionConnectSelectionTest {
    private fun server(name:String,bs:Boolean=false)=ServerImport.Server(name,name,if(bs)"csqtt" else "vless","localhost",1,null,null,null,if(bs)JSONObject().put("type","csqtt")else null)
    private val vpn=server("vpn-slow")
    private val vpnFast=server("vpn-fast")
    private val bs=server("bs-slow",true)
    private val bsFast=server("bs-fast",true)
    private val all=listOf(vpn,bs,vpnFast,bsFast)
    private fun probes(vararg results:Pair<ServerImport.Server,Long>)=JSONObject().apply {results.forEach {(entry,delay)->put(entry.id,JSONObject().put("delay",delay))}}
    @Test fun vpnConnectReplacesWhitelistWithLowestVpnPing() {
        assertEquals(vpnFast,SectionConnectSelection.choose(all,bs.id,false,probes(vpn to 100,vpnFast to 30,bsFast to 1)))
    }
    @Test fun whitelistConnectReplacesVpnWithLowestWhitelistPing() {
        assertEquals(bsFast,SectionConnectSelection.choose(all,vpn.id,true,probes(bs to 100,bsFast to 30,vpnFast to 1)))
    }
    @Test fun currentSamePoolChoiceIsNeverChangedEvenWhenSlower() {
        assertEquals(vpn,SectionConnectSelection.choose(all,vpn.id,false,probes(vpn to 100,vpnFast to 1)))
        assertEquals(bs,SectionConnectSelection.choose(all,bs.id,true,probes(bs to 100,bsFast to 1)))
    }
    @Test fun deletedOrEmptySelectionAlsoPicksTargetPoolBest() {
        assertEquals(vpnFast,SectionConnectSelection.choose(all,"removed",false,probes(vpnFast to 30)))
        assertEquals(bsFast,SectionConnectSelection.choose(all,"",true,probes(bsFast to 30)))
    }
    @Test fun zeroIsValidAndFailedMeasurementDoesNotWin() {
        assertEquals(vpnFast,SectionConnectSelection.choose(all,bs.id,false,probes(vpn to -1,vpnFast to 0)))
    }
    @Test fun unmeasuredBeforeFailedButAfterSuccessful() {
        assertEquals(vpnFast,SectionConnectSelection.choose(all,bs.id,false,probes(vpn to -1)))
        assertEquals(vpn,SectionConnectSelection.choose(all,bs.id,false,probes(vpn to 900)))
    }
    @Test fun noMeasurementsAndEqualPingsPreserveCatalogueOrder() {
        assertEquals(vpn,SectionConnectSelection.choose(all,bs.id,false,JSONObject()))
        assertEquals(bs,SectionConnectSelection.choose(all,vpn.id,true,probes(bs to 40,bsFast to 40)))
    }
    @Test fun emptyTargetPoolDoesNotLeakOtherFamily() {
        assertNull(SectionConnectSelection.choose(listOf(bs),bs.id,false,probes(bs to 1)))
        assertNull(SectionConnectSelection.choose(listOf(vpn),vpn.id,true,probes(vpn to 1)))
        assertNull(SectionConnectSelection.choose(emptyList(),"",false,JSONObject()))
    }
    @Test fun selectionDoesNotEditCatalogueOrProbeResults() {
        val p=probes(vpn to 80,vpnFast to 20);val before=p.toString();val list=all.toList()
        SectionConnectSelection.choose(all,bs.id,false,p)
        assertEquals(list,all);assertEquals(before,p.toString())
    }
}
