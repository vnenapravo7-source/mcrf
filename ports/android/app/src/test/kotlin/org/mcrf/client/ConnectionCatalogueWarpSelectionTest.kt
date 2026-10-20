package org.mcrf.client

import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ConnectionCatalogueWarpSelectionTest {
    private fun account() = JSONObject().put("id","catalogue-fixture").put("token","catalogue-fixture-only")
        .put("private_key",Base64.getEncoder().encodeToString(ByteArray(32) {(it+1).toByte()}))
        .put("peer_public_key",WarpProfile.PEER_KEY).put("ipv4","172.16.0.2").toString()
    private fun replacement(config:String)=config.replace("engage.cloudflareclient.com:2408","162.159.192.5:4500")

    @Test fun markedRescanPreservesPoolAtomicallyWithoutOldProbe() {
        val data=ConnectionCatalogue(""); val config=WarpProfile.initial(account()).original
        val old=data.saveWarp(account(),config);data.setAuto(old,true);data.recordProbe(old,42,100)
        data.add("trojan://fixture@example.com:443?security=tls#Other")
        val other=data.entries().first {it.id!=old}.id;data.setAuto(other,true);data.recordProbe(other,23,101)
        val next=data.saveWarp(account(),replacement(config),old)
        val restored=ConnectionCatalogue(data.serialize())
        assertNotEquals(old,next);assertEquals(setOf(next,other),restored.autoIds())
        assertFalse(restored.probes().has(old));assertFalse(restored.probes().has(next))
        assertEquals(23L,restored.probes().getJSONObject(other).getLong("delay"))
        assertNull(restored.warpAccount(old));assertEquals(account(),restored.warpAccount(next))
    }
    @Test fun unmarkedRescanNeverOptsIntoAutoPool() {
        val data=ConnectionCatalogue("");val config=WarpProfile.initial(account()).original
        val old=data.saveWarp(account(),config);data.recordProbe(old,42,100)
        val next=data.saveWarp(account(),replacement(config),old)
        val restored=ConnectionCatalogue(data.serialize())
        assertFalse(next in restored.autoIds());assertFalse(restored.probes().has(next));assertFalse(restored.probes().has(old))
    }
    @Test fun removingRescannedProfilePrunesAccountSelectionAndProbe() {
        val data=ConnectionCatalogue("");val config=WarpProfile.initial(account()).original
        val old=data.saveWarp(account(),config);data.setAuto(old,true)
        val next=data.saveWarp(account(),replacement(config),old);data.recordProbe(next,7,102);data.remove(next)
        val restored=ConnectionCatalogue(data.serialize())
        assertNull(restored.warpAccount(next));assertTrue(restored.autoIds().isEmpty());assertFalse(restored.probes().has(next))
        assertFalse(restored.serialize().contains("catalogue-fixture-only"))
    }
}
