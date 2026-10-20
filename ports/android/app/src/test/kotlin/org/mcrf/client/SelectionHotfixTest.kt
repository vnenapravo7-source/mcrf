package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SelectionHotfixTest {
    private fun entry(name:String,bs:Boolean)=ServerImport.Server(name,name,if(bs)"csqtt"else"vless","localhost",1,null,null,null,if(bs)JSONObject().put("type","csqtt")else null)
    private val vpn=entry("vpn",false);private val fast=entry("fast",false)
    private val bs=entry("bs",true);private val bsFast=entry("bs-fast",true)
    private val entries=listOf(vpn,bs,fast,bsFast)
    private val probes=JSONObject().put(vpn.id,JSONObject().put("delay",100)).put(fast.id,JSONObject().put("delay",10))
        .put(bs.id,JSONObject().put("delay",90)).put(bsFast.id,JSONObject().put("delay",9))
    private fun source(name:String)=listOf(File("src/main/kotlin/org/mcrf/client/$name.kt"),File("app/src/main/kotlin/org/mcrf/client/$name.kt"))
        .first {it.isFile}.readText()
    @Test fun wrongPoolConnectWorksBothDirectionsInManualAndAutomaticModes() {
        for(auto in listOf(false,true))for(marked in listOf(false,true)) {
            val toVpn=SectionConnectSelection.start(entries,bs.id,false,auto,marked,probes)!!
            val toBs=SectionConnectSelection.start(entries,vpn.id,true,auto,marked,probes)!!
            assertEquals(fast,toVpn.server);assertFalse(toVpn.automatic)
            assertEquals(bsFast,toBs.server);assertFalse(toBs.automatic)
        }
    }
    @Test fun validManualSelectionIsPreservedAndValidAutomaticPolicyStillWorks() {
        assertEquals(SectionConnectSelection.Start(vpn,false),SectionConnectSelection.start(entries,vpn.id,false,false,false,probes))
        assertEquals(SectionConnectSelection.Start(bs,true),SectionConnectSelection.start(entries,bs.id,true,true,true,probes))
    }
    @Test fun emptyAutomaticPoolFallsBackToLowestPingWithoutChooseServerPrompt() {
        val start=SectionConnectSelection.start(entries,vpn.id,false,true,false,probes)!!
        assertEquals(fast,start.server);assertFalse(start.automatic)
    }
    @Test fun deletedSelectionAndEmptyTargetPoolHaveDifferentOutcomes() {
        assertEquals(bsFast,SectionConnectSelection.start(entries,"removed",true,true,true,probes)?.server)
        assertNull(SectionConnectSelection.start(listOf(bs),bs.id,false,false,false,probes))
    }
    @Test fun idleTapsSaveOnlyAndNeverStartAConnection() {
        assertEquals(ServerTapPolicy.Action(true,false),ServerTapPolicy.action("old",false,"new",false,""))
        assertEquals(ServerTapPolicy.Action(false,false),ServerTapPolicy.action("same",false,"same",false,""))
    }
    @Test fun connectedTapMovesToClickedServerInEitherPool() {
        for(from in listOf(vpn,bs))for(to in listOf(vpn,bs,fast,bsFast)) {
            val action=ServerTapPolicy.action(from.id,false,to.id,true,from.id)
            assertEquals(from.id!=to.id,action.reconnect)
            assertEquals(from.id!=to.id,action.save)
        }
    }
    @Test fun explicitTapLeavesAutomaticModeAndReconnectsEvenIfCurrentWinnerMatches() {
        assertEquals(ServerTapPolicy.Action(true,true),ServerTapPolicy.action(vpn.id,true,vpn.id,true,vpn.id))
    }
    @Test fun selectedIdIsNotProofThatRunningServerAlreadyMatches() {
        assertEquals(ServerTapPolicy.Action(false,true),ServerTapPolicy.action(bs.id,false,bs.id,true,vpn.id))
    }
    @Test fun allVisualConnectEntrypointsDelegateWithoutOldWhitelistSelectionGuard() {
        val main=source("MainActivity")
        val whitelist=main.substringAfter("private fun whiteLists()").substringBefore("private fun toggleConnection()")
        assertTrue(whitelist.contains("connect=connectionButton {toggleConnection()}"))
        assertFalse(whitelist.contains("toast(\"Выбери подключение"))
        val edge=main.substringAfter("private fun toggleEdgeConnection").substringBefore("private fun connectionTargetView")
        assertFalse(edge.contains("toast(\"Выбери подключение"))
        assertTrue(edge.contains("prepareConnection(false,requestedWhitelists=whitelists)"))
        assertTrue(main.contains("SectionConnectSelection.start("))
    }
    @Test fun tappingServerDoesNotRebuildPageAndUsesClickedFamilyForReconnect() {
        val main=source("MainActivity")
        val select=main.substringAfter("fun select(id: String)").substringBefore("fun setAutomatic")
        assertFalse(select.contains("render("))
        assertTrue(select.contains("ServerTapPolicy.action("))
        assertTrue(select.contains("serverSwitch.request(id,entry.carrier!=null"))
        assertTrue(select.contains(".setAction(McrfVpnService.STOP).putExtra(ServerSwitchTransition.EXTRA,request.token)"))
        val rows=source("ServerRow")
        assertTrue(rows.contains("setOnClickListener {onSelect.run()}"))
        assertFalse(rows.contains("check.toggle()"))
        assertTrue(rows.contains("fun showChosen(value:Boolean)"))
        assertTrue(source("ServersPage").contains("row.showChosen(id==selected)"))
    }
    @Test fun activeHandoverKeepsOrdinaryStartCleanupAndConsentGates() {
        val main=source("MainActivity")
        assertTrue(main.contains("VpnService.prepare(this)"))
        assertTrue(main.contains("ClientPrimarySelection.validateNative(this,prepared.toString(),vpn)"))
        val service=source("McrfVpnService").substringAfter("if (intent.action != START)").substringBefore("return START_STICKY")
        assertTrue(service.indexOf("closeCore()")>=0)
        assertTrue(service.indexOf("startSource(")>service.indexOf("closeCore()"))
    }
}
