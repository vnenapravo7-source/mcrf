package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ConnectionSectionsTest {
    private fun entry(kind:String,carrier:Boolean)=ServerImport.Server("owned-$kind","Owned $kind",kind,"localhost",1,null,null,null,if(carrier)JSONObject().put("type",kind)else null)
    @Test fun ordinaryAndAllExternalCarriersAreDisjointWithoutDroppingProfiles(){
        val ordinary=listOf(entry("vless",false),entry("wireguard",false),entry("sing-box",false))
        val carriers=listOf("csqtt","qwdtt","wdtt","openflux","olcrtc","dnstt").map {entry(it,true)}
        val all=ordinary+carriers
        assertEquals(ordinary,ConnectionSections.entries(all,false))
        assertEquals(carriers,ConnectionSections.entries(all,true))
        assertEquals(all.size,ConnectionSections.entries(all,false).size+ConnectionSections.entries(all,true).size)
        assertEquals(ordinary+carriers,all)
        carriers.forEach {assertFalse(ConnectionSections.belongs(it,false))}
        ordinary.forEach {assertFalse(ConnectionSections.belongs(it,true))}
    }
    @Test fun versionedCs25StaysInWhitelistAndIsNotReclassifiedByProtocolLabel(){
        val old=entry("csqtt",true)
        val modern=ServerImport.Server("owned25","New CSQTT","csqtt","localhost",1,null,null,null,JSONObject().put("type","csqtt").put("csqtt_generation","2.5"))
        assertEquals(listOf(old,modern),ConnectionSections.entries(listOf(old,modern),true))
        assertTrue(ConnectionSections.entries(listOf(old,modern),false).isEmpty())
    }
    @Test fun explicitSectionStartUsesOnlyItsOwnAutomaticPolicy(){
        assertEquals(AutoSelection.LATENCY,ConnectionSections.policy(false))
        assertEquals(AutoSelection.BS_ENGINES,ConnectionSections.policy(true))
    }
    @Test fun importedCarrierLinksOpenWhitelistsAndMixedSubscriptionsKeepTheirSection(){
        assertEquals(ClientSettingsNavigation.WHITELISTS,ConnectionSections.importDestination(listOf(entry("csqtt",true),entry("openflux",true))))
        assertEquals("Главная",ConnectionSections.importDestination(listOf(entry("vless",false))))
        assertNull(ConnectionSections.importDestination(listOf(entry("vless",false),entry("qwdtt",true))))
        assertNull(ConnectionSections.importDestination(emptyList()))
    }
}
