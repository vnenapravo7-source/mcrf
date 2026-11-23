package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ServerSectionSymmetryTest {
    private val link="vless://11111111-1111-4111-8111-111111111111@fixture.invalid:443?security=tls#Original"
    private fun catalogue(source:String=link)=ConnectionCatalogue("").apply {add(source)}
    private fun moved(model:ConnectionCatalogue,bs:Boolean):ConnectionCatalogue =
        ConnectionCatalogue(ServerSectionChoice.move(JSONObject(model.serialize()),model.entries().single().id,bs).toString())

    private fun assertSection(model:ConnectionCatalogue,bs:Boolean) {
        val entry=model.entries().single();val entries=listOf(entry)
        assertEquals(bs,entry.whitelists)
        assertEquals(bs,ConnectionSections.belongs(entry,true))
        assertEquals(!bs,ConnectionSections.belongs(entry,false))
        assertEquals(bs,AutoSelection.eligible(entry,AutoSelection.BS_ENGINES))
        assertEquals(!bs,AutoSelection.eligible(entry,AutoSelection.LATENCY))
        assertEquals(listOf(entry.id),AutoSelection.startup(entries,setOf(entry.id),JSONObject(),0,ConnectionSections.policy(bs)).map {it.id})
        assertTrue(AutoSelection.startup(entries,setOf(entry.id),JSONObject(),0,ConnectionSections.policy(!bs)).isEmpty())
        assertEquals(bs,RoutingConnectionBinding.eligible(entry,true))
        assertEquals(!bs,RoutingConnectionBinding.eligible(entry,false))
        assertEquals(entries,RouteServerPlan.fastestEntries(entries,bs))
        assertTrue(RouteServerPlan.fastestEntries(entries,!bs).isEmpty())
        assertEquals(entry.id,SectionConnectSelection.choose(entries,"",bs,JSONObject())?.id)
        assertNull(SectionConnectSelection.choose(entries,"",!bs,JSONObject()))
        assertTrue(ServerSectionChoice.belongs(entry,bs,ServerSectionChoice.assignments(JSONObject(model.serialize()))))
    }

    @Test fun roundtripPreservesProtectedFieldsEditsSubscriptionsAndMarks() {
        val original=ConnectionCatalogue("").apply {replaceSubscription("https://fixture.invalid/sub",ServerImport.read(link))}
        val entry=original.entries().single()
        original.edit(entry.id,ConnectionEdits.snapshot(entry).put("name","Edited"))
        original.setAuto(entry.id,true)
        val expected=JSONObject(original.serialize()).apply {remove(ServerSectionChoice.KEY)}
        val bs=moved(original,true);assertSection(bs,true)
        assertEquals("VPN",ServerSectionChoice.actionLabel(bs.entries().single()))
        val vpn=moved(bs,false);assertSection(vpn,false)
        assertEquals("БС",ServerSectionChoice.actionLabel(vpn.entries().single()))
        assertEquals(entry.id,vpn.entries().single().id)
        assertEquals(link,vpn.entries().single().original)
        assertTrue(RoutingDefaultsEdits.equivalent(expected,JSONObject(vpn.serialize()).apply {remove(ServerSectionChoice.KEY)}))
        assertEquals(vpn.serialize(),moved(vpn,false).serialize())
    }

    @Test fun vpnChoiceSurvivesRefreshRenameAndNetworkProfileApplication() {
        val original=ConnectionCatalogue("").apply {replaceSubscription("https://fixture.invalid/sub",ServerImport.read(link))}
        val vpn=moved(moved(original,true),false)
        val renamed=link.substringBefore('#')+"#Renamed"
        vpn.replaceSubscription("https://fixture.invalid/sub",ServerImport.read(renamed))
        assertSection(vpn,false)
        val routes=RouteRules.defaults().also {RoutingGroups.ensure(it)}
        val config=NetworkProfileConfiguration.capture(routes.toString(),vpn.serialize(),JSONObject())
        val values=mapOf("connections.enc" to vpn.serialize(),"routing.enc" to routes.toString(),"preferences.enc" to "{}")
        val applied=NetworkProfileSwitch.apply(config,values)
        assertSection(ConnectionCatalogue(applied.getValue("connections.enc")),false)
        assertEquals(false,ServerSectionChoice.assignments(JSONObject(applied.getValue("connections.enc")))[vpn.entries().single().id])
    }

    @Test fun ordinaryBsCanReturnToVpnAndNamesDoNotDetermineType() {
        val standard=ServerImport.read(link).single()
        val bs=ServerImport.Server(standard.original,"OpenFlux S3 CSQTT",standard.protocol,standard.host,standard.port,
            standard.outbound,null,null,null,emptyList(),true)
        assertEquals("VPN",ServerSectionChoice.actionLabel(bs))
        assertFalse(ServerSectionChoice.apply(bs,mapOf(bs.id to false)).whitelists)
        assertTrue(ServerSectionChoice.canMove(standard))
    }

    @Test fun everyProtectedKindAndProfileMarkerHidesTheAction() {
        val kinds=setOf("s3bypass","s3-bypass","fedarisha","qwdtt","wdtt","csqtt","csqtt20","csqtt215","csqtt25","openflux")
        for(kind in kinds) {
            val protocol=ServerImport.Server("fixture-$kind","Ordinary",kind,"fixture.invalid",443,null,null,null)
            assertFalse(kind,ServerSectionChoice.canMove(protocol));assertNull(ServerSectionChoice.actionLabel(protocol))
            val carrier=ServerImport.Server("fixture-carrier-$kind","Ordinary","socks","fixture.invalid",443,null,null,null,JSONObject().put("type",kind))
            assertFalse(kind,ServerSectionChoice.canMove(carrier));assertNull(ServerSectionChoice.actionLabel(carrier))
            assertThrows(IllegalArgumentException::class.java){ServerSectionChoice.apply(carrier,mapOf(carrier.id to false))}
        }
        val config=JSONObject().put("outbounds",JSONArray().put(JSONObject().put("protocol","fedarisha")))
        val wrapped=ServerImport.Server("fixture-config","Ordinary","sing-box","fixture.invalid",443,null,null,config)
        assertFalse(ServerSectionChoice.canMove(wrapped))
    }

    private fun fixedSources():List<String> {
        val swg=listOf("qwdtt","wdtt","csqtt").map {kind->JSONObject().put("type",kind).put("name","Ordinary")
            .put("server","fixture.invalid").put("server_port",if(kind=="csqtt")46000 else 56000)
            .put("password","fixture-secret").put("hashes",JSONArray().put("fixture-call-0123456789abcdef")).put("workers",18).toString()}
        val s3="""{"type":"s3bypass","name":"Ordinary","settings":{"storage":{"type":"s3","bucket":"fixture-bucket","endpoint":"https://storage.fixture.invalid","region":"fixture-region","accessKey":"fixture-access","secretKey":"fixture-secret"}}}"""
        val flux=JSONObject().put("type","openflux").put("name","Ordinary").put("negotiate",true).put("secret","fixture-openflux-secret")
            .put("transports",JSONArray().put(JSONObject().put("type","direct").put("dial","fixture.invalid:443"))).toString()
        return swg+listOf(s3,flux)
    }

    @Test fun modelAndCommitRejectBothDirectionsForActualEngineProfilesWithoutMutation() {
        for(source in fixedSources()) {
            val model=catalogue(source);val entry=model.entries().single();val document=JSONObject(model.serialize());val before=document.toString()
            assertNull(ServerSectionChoice.actionLabel(entry))
            for(bs in listOf(false,true)) {
                assertThrows(IllegalArgumentException::class.java){ServerSectionChoice.move(document,entry.id,bs)}
                assertThrows(IllegalArgumentException::class.java){ServerEditorCommit.prepareMove(document,entry,bs)}
                assertEquals(before,document.toString())
            }
            // A pre-existing harmless BS mark can still be read; injected VPN overrides cannot.
            val legacy=JSONObject(before).put(ServerSectionChoice.KEY,JSONObject().put(entry.id,"bs"))
            assertTrue(ConnectionCatalogue(legacy.toString()).entries().single().whitelists)
            legacy.getJSONObject(ServerSectionChoice.KEY).put(entry.id,"vpn")
            assertThrows(IllegalArgumentException::class.java){ConnectionCatalogue(legacy.toString())}
        }
    }

    @Test fun staleMoveRejectsSectionChangesEditsAndDeletedEntries() {
        val original=catalogue();val expected=original.entries().single()
        val bs=moved(original,true);val changed=JSONObject(bs.serialize());val before=changed.toString()
        assertThrows(IllegalArgumentException::class.java){ServerEditorCommit.prepareMove(changed,expected,false)}
        assertEquals(before,changed.toString())
        original.edit(expected.id,ConnectionEdits.snapshot(expected).put("name","Changed"))
        assertThrows(IllegalArgumentException::class.java){ServerEditorCommit.prepareMove(JSONObject(original.serialize()),expected,true)}
        original.remove(expected.id)
        assertThrows(IllegalArgumentException::class.java){ServerEditorCommit.prepareMove(JSONObject(original.serialize()),expected,true)}
    }
}
