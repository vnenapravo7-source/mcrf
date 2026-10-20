package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Queued with the next complete source batch; not independently executed. */
class EdgeSectionHandoverTest {
    private fun entry(id:String,white:Boolean)=ServerImport.Server(id,id,if(white)"csqtt"else"vless","localhost",1,null,null,null,if(white)JSONObject().put("type","csqtt")else null)
    @Test fun oppositeSectionOnUsesFastestTargetPoolInBothDirections() {
        val vpn=entry("vpn",false);val vpnFast=entry("vpn-fast",false)
        val bs=entry("bs",true);val bsFast=entry("bs-fast",true)
        val all=listOf(vpn,bs,vpnFast,bsFast)
        val probes=JSONObject().put(vpn.id,JSONObject().put("delay",80)).put(vpnFast.id,JSONObject().put("delay",20))
            .put(bs.id,JSONObject().put("delay",90)).put(bsFast.id,JSONObject().put("delay",30))
        for(white in listOf(false,true)) {
            assertEquals(EdgeConnectionState.Action.SWITCH,EdgeConnectionState.action(white,true,true,!white,true))
            assertEquals(if(white)bsFast else vpnFast,SectionConnectSelection.choose(all,"",white,probes))
        }
    }
    @Test fun emptyTargetPoolCannotSelectOppositeServer() {
        assertNull(SectionConnectSelection.choose(listOf(entry("vpn",false)),"",true,JSONObject()))
        assertNull(SectionConnectSelection.choose(listOf(entry("bs",true)),"",false,JSONObject()))
    }
    @Test fun edgeUsesSharedRetirementPathAndNeverDirectlyStartsOrStopsOnMissingPool() {
        val main=listOf(File("src/main/kotlin/org/mcrf/client/MainActivity.kt"),File("app/src/main/kotlin/org/mcrf/client/MainActivity.kt")).first {it.isFile}.readText()
        val edge=main.substringAfter("private fun toggleEdgeConnection").substringBefore("private fun resumeWantedOnLaunch")
        val handover=edge.substringAfter("EdgeConnectionState.Action.SWITCH->").substringBefore("EdgeConnectionState.Action.NONE")
        assertTrue(handover.contains("SectionConnectSelection.choose(catalogue.entries(),\"\",whitelists,catalogue.probes())"))
        assertTrue(handover.contains("if(target==null)toast("))
        assertTrue(handover.contains("else select(target.id)"))
        assertFalse(handover.contains("startService("))
        assertFalse(handover.contains("prepareConnection("))
        assertFalse(handover.contains("navigate("))
        assertFalse(edge.contains("Сначала отключи соединение"))
        assertTrue(edge.contains("prepareConnection(false,requestedWhitelists=whitelists)"))
    }
}
