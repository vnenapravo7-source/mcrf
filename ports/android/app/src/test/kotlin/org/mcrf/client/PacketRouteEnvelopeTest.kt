package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

/** Queued source fixtures only: whole P1 must be ready before execution. */
class PacketRouteEnvelopeTest {
    private val key=Base64.getEncoder().encodeToString(ByteArray(32){1})
    private val original="[Interface]\nPrivateKey=$key\nAddress=10.77.0.2/32,fd77::2/128\nDNS=10.77.0.1\n[Peer]\nPublicKey=$key\nEndpoint=192.0.2.1:51820\nAllowedIPs=10.77.0.0/24,fd77::/64\nPersistentKeepalive=25\n"
    private fun plan()=CompactWireGuardPlan.prepare(WireGuardProfile.parse(original),"192.0.2.1")
    private fun rule(target:PacketIpRoutes.Target)=PacketIpRoutes.Rule(listOf("10.77.0.3/32","fd77::3/128"),target)
    @Test fun orderedDnsIsDetachedAndBoundToSameRouteSnapshotWithoutChangingProfile(){
        val original=plan();val secret=original.secretConfiguration();val list=mutableListOf("10.77.0.1")
        val captured=PacketRouteEnvelope.withDns(PacketRouteEnvelope.capture(original.routes,emptyList()),list)
        list[0]="10.77.0.4"
        assertEquals(2,JSONObject(captured.settings).getInt("schema"));assertEquals(listOf("10.77.0.1"),captured.dns)
        assertEquals(captured.dns,PacketRouteEnvelope.decode(captured.settings,original.routes).dns)
        assertEquals(captured.projection.peer,PacketRouteEnvelope.workerPlan(original,captured.settings).routes)
        assertEquals(secret,original.secretConfiguration());assertEquals(listOf("10.77.0.1"),original.dns)
        assertThrows(UnsupportedOperationException::class.java){(captured.dns as MutableList).clear()}
    }
    @Test fun workerIndependentlyRejectsDnsRebindingOutsidePeerOrOriginalProfile(){
        val original=plan();val raw=PacketRouteEnvelope.capture(original.routes,emptyList())
        for(server in listOf("10.88.0.1","10.77.0.4")){
            val captured=PacketRouteEnvelope.withDns(raw,listOf(server))
            assertThrows(IllegalArgumentException::class.java){PacketRouteEnvelope.workerPlan(original,captured.settings)}
        }
        val blocked=PacketRouteEnvelope.capture(original.routes,listOf(PacketIpRoutes.Rule(listOf("10.77.0.1/32"),PacketIpRoutes.Target.BLOCK)))
        val captured=PacketRouteEnvelope.withDns(blocked,original.dns)
        assertThrows(IllegalArgumentException::class.java){PacketRouteEnvelope.workerPlan(original,captured.settings,PacketRouteEnvelope.Admission.ATOMIC_IP_BLOCK)}
    }
    @Test fun dnsWireRejectsUnknownManagedModesCoercedValuesOrSilentIpv4Policy(){
        val original=plan();val settings=PacketRouteEnvelope.withDns(PacketRouteEnvelope.capture(original.routes,emptyList()),original.dns).settings
        for(change in listOf<(JSONObject)->Unit>(
            {it.getJSONObject("dns").put("mode","doh")},
            {it.getJSONObject("dns").put("ipv4Compatibility",true)},
            {it.getJSONObject("dns").put("servers",JSONArray().put(1))},
            {it.getJSONObject("dns").put("servers",JSONArray().put("dns.example.invalid"))},
            {it.put("schema","2")}, {it.put("schema",4294967298L)}
        )){val json=JSONObject(settings);change(json);assertThrows(IllegalArgumentException::class.java){PacketRouteEnvelope.decode(json.toString(),original.routes)}}
        assertThrows(IllegalArgumentException::class.java){PacketRouteEnvelope.withDns(PacketRouteEnvelope.decode("{}",original.routes),original.dns)}
    }
    @Test fun hostAndWorkerUseSameNarrowingWithoutRewritingOriginalProfile() {
        val before=plan();val settings=PacketRouteEnvelope.capture(before.routes,listOf(rule(PacketIpRoutes.Target.DIRECT)))
        val after=PacketRouteEnvelope.workerPlan(before,settings.settings)
        assertEquals(settings.projection.peer,after.routes)
        assertEquals(settings.projection.tun,settings.projection.peer)
        assertEquals(before.addresses,after.addresses);assertEquals(before.dns,after.dns);assertEquals(before.mtu,after.mtu)
        val kept={text:String->text.lineSequence().filterNot {it.startsWith("allowed_ip=")}.toList()}
        assertEquals(kept(before.secretConfiguration()),kept(after.secretConfiguration()))
        assertEquals(plan().secretConfiguration(),before.secretConfiguration())
        assertFalse(settings.toString().contains(key))
    }
    @Test fun tamperedIndependentTunOrPeerArrayIsRejected() {
        val before=plan();val captured=PacketRouteEnvelope.capture(before.routes,listOf(rule(PacketIpRoutes.Target.DIRECT)))
        for(field in listOf("tun","peer")) {
            val changed=JSONObject(captured.settings).put(field,JSONArray(before.routes))
            assertThrows(IllegalArgumentException::class.java){PacketRouteEnvelope.workerPlan(before,changed.toString())}
        }
    }
    @Test fun blockProjectionCannotEnableUnacceptedNativeDrop() {
        val before=plan();val captured=PacketRouteEnvelope.capture(before.routes,listOf(rule(PacketIpRoutes.Target.BLOCK)))
        assertTrue(captured.requiresCapturedDrop)
        assertThrows(IllegalArgumentException::class.java){PacketRouteEnvelope.workerPlan(before,captured.settings)}
    }
    @Test fun atomicBlockRequiresItsOwnExactDeclarationAndStillChecksBothProjections(){
        assertEquals(PacketRouteEnvelope.Admission.LEGACY_IP,PacketRouteEnvelope.admission(null))
        for(marker in listOf<Any>(true,false,1,"true","capture_v6_native","atomic_tun_peer_v2")){
            assertThrows(IllegalArgumentException::class.java){PacketRouteEnvelope.admission(marker)}
        }
        val admission=PacketRouteEnvelope.admission(PacketRouteEnvelope.BLOCK_CONTRACT)
        val before=plan();val secret=before.secretConfiguration()
        val captured=PacketRouteEnvelope.capture(before.routes,listOf(rule(PacketIpRoutes.Target.BLOCK)))
        val admitted=PacketRouteEnvelope.workerPlan(before,captured.settings,admission)
        assertEquals(captured.projection.peer,admitted.routes)
        for(ip in listOf("10.77.0.3/32","fd77::3/128")){
            assertTrue(captured.projection.tun.any {ClientTunCidr.parse(it).overlaps(ClientTunCidr.parse(ip))})
            assertFalse(admitted.routes.any {ClientTunCidr.parse(it).overlaps(ClientTunCidr.parse(ip))})
        }
        val tampered=JSONObject(captured.settings).put("peer",JSONArray(before.routes)).toString()
        assertThrows(IllegalArgumentException::class.java){PacketRouteEnvelope.workerPlan(before,tampered,admission)}
        assertEquals(secret,before.secretConfiguration())
        // Pure negotiation/projection only, NOT installed metadata/SHA/native acceptance.
    }
    @Test fun unknownFieldsAndMismatchedOriginalAllowedIpsFailClosed() {
        val before=plan();val settings=PacketRouteEnvelope.capture(before.routes,emptyList()).settings
        assertThrows(IllegalArgumentException::class.java){PacketRouteEnvelope.decode(JSONObject(settings).put("dns",JSONObject()).toString(),before.routes)}
        assertThrows(IllegalArgumentException::class.java){PacketRouteEnvelope.decode(settings,listOf("10.99.0.0/24"))}
        assertSame(before,PacketRouteEnvelope.workerPlan(before,"{}"))
    }
}
