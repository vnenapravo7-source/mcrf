package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Section assignment must survive catalogue transactions and reach route/startup admission. */
class ServerSectionIntegrationTest {
    private val link="socks://127.0.0.1:1080#Owned"
    private fun catalogue():ConnectionCatalogue {
        val original=ConnectionCatalogue("").apply {add(link)}
        val id=original.entries().single().id
        return ConnectionCatalogue(ServerSectionChoice.move(JSONObject(original.serialize()),id).toString())
    }
    @Test fun markedOutboundIsOnlyBsWithoutBecomingCarrier() {
        val model=catalogue();val entry=model.entries().single()
        assertTrue(ConnectionSections.belongs(entry,true))
        assertFalse(ConnectionSections.belongs(entry,false))
        assertNull(entry.carrier)
        assertTrue(AutoSelection.eligible(entry,AutoSelection.BS_ENGINES))
        assertFalse(AutoSelection.eligible(entry,AutoSelection.LATENCY))
        assertTrue(RoutingConnectionBinding.eligible(entry,true))
        assertFalse(RoutingConnectionBinding.eligible(entry,false))
        assertEquals(listOf(entry.id),RouteServerPlan.fastestEntries(listOf(entry),true).map {it.id})
        assertTrue(RouteServerPlan.fastestEntries(listOf(entry),false).isEmpty())
    }
    @Test fun ordinaryBsCandidateUsesItsExactEditedOutbound() {
        val model=catalogue();val id=model.entries().single().id
        model.edit(id,JSONObject().put("name","Edited").put("outbound",JSONObject(model.entries().single().outbound!!.toString()).put("server_port",1081)))
        val entry=ConnectionCatalogue(model.serialize()).entries().single()
        val source=BsPlan.build(listOf(entry),60,50,"Cloudflare")
        val candidate=BsPlan.candidates(source.getJSONObject("mcrf_bs")).single()
        assertFalse(candidate.has("profile"))
        val outputs=candidate.getJSONObject("source").getJSONArray("outbounds")
        val node=(0 until outputs.length()).map {outputs.getJSONObject(it)}.single {it.optString("tag")==ServerImport.tag(entry)}
        assertEquals(1081,node.getInt("server_port"))
        assertTrue(entry.whitelists)
        assertEquals("Edited",candidate.getString("name"))
    }
    @Test fun deletingMarkedServerPrunesOverrideAndDoesNotMoveOtherServers() {
        val model=catalogue();val id=model.entries().single().id
        model.add("socks://127.0.0.2:1080#Other");model.remove(id)
        val restored=ConnectionCatalogue(model.serialize())
        assertFalse(restored.entries().single().whitelists)
        assertTrue(ServerSectionChoice.whitelistIds(JSONObject(restored.serialize())).isEmpty())
    }
}
