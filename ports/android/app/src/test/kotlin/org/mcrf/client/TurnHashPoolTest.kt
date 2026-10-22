package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TurnHashPoolTest {
    private fun hash(index:Int)=java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32){index.toByte()})
    @Test fun canonicalDedupePreservesServerFirstAndAllMoreThanSixCalls() {
        val values=(1..80).map(::hash)
        val first="https://vk.ru/call/join/${values.first()}"
        assertEquals(listOf(first)+values.drop(1),VkHashPool.append(listOf(first),values+"https://vk.com:443/call/join/${values.first()}"))
        for(kind in listOf("qwdtt","wdtt","csqtt"))assertEquals(80,VkRelayProtocol.calls(values,kind).size)
        assertEquals("not-a-link",VkHashPool.key("not-a-link"))
        assertNotEquals(values.first(),VkHashPool.key("https://vk.ru.evil.invalid/call/join/${values.first()}"))
    }
    @Test fun legacyProfilesShareAllCallsWithoutSixOrSixtyFourClamps() {
        for(kind in listOf("qwdtt","wdtt","csqtt")) {
            val raw=JSONObject().put("type",kind).put("server","fixture.invalid").put("server_port",46000)
                .put("password","fixture-only").put("workers",18).put("hashes",JSONArray((1..80).map(::hash)))
            val entry=SwgProfile.fromJson("fixture",raw)
            assertEquals(80,entry.carrier!!.getJSONArray("hashes").length())
            assertEquals(80,ServerImport.read(SwgProfile.share(entry.carrier!!)).single().carrier!!.getJSONArray("hashes").length())
        }
    }
    @Test fun extendedPoolsRequireNewCapabilityInAdditionToExistingMode() {
        val raw=JSONObject().put("type","csqtt").put("csqtt_generation","2.5").put("hashes",JSONArray((1..7).map(::hash)))
        assertEquals(setOf("org.mcrf.engines.csqtt25_swg_panel","org.mcrf.engines.turn_hash_pool"),CarrierOptionContract.requiredAll(raw))
        assertEquals(setOf("org.mcrf.engines.wdtt_dual_family"),CarrierOptionContract.requiredAll(JSONObject().put("type","wdtt")))
        assertTrue(CarrierOptionContract.requiredAll(JSONObject().put("type","qwdtt").put("hashes",JSONArray((1..6).map(::hash)))).isEmpty())
    }
    @Test fun fastestRouteShortcutsSeparateVpnAndBsWithoutChangingCatalogueOrRule() {
        val vpn=ServerImport.read("socks5://127.0.0.1:1080#VPN").single()
        val raw=JSONObject().put("type","wdtt").put("server","fixture.invalid").put("server_port",46000)
            .put("password","fixture-only").put("hashes",JSONArray().put(hash(1)))
        val bs=SwgProfile.fromJson("fixture",raw)
        val entries=listOf(vpn,bs,vpn)
        assertEquals(listOf(vpn.id),RouteServerPlan.fastestEntries(entries,false).map {it.id})
        assertEquals(listOf(bs.id),RouteServerPlan.fastestEntries(entries,true).map {it.id})
        assertEquals(3,entries.size)
    }
    @Test fun recentChoiceMovesOnlyKnownMemberAndKeepsFastFallbackOrder() {
        val ordered=listOf("fast","recent","slow")
        assertEquals(listOf("recent","fast","slow"),BsAutoSelectionPolicy.order(ordered,"recent"){it})
        assertEquals(ordered,BsAutoSelectionPolicy.order(ordered,"deleted"){it})
        assertEquals(ordered,BsAutoSelectionPolicy.order(ordered,""){it})
        assertTrue(BsAutoSelectionPolicy.order(emptyList<String>(),"deleted"){it}.isEmpty())
    }
    @Test fun boundedAttemptsKeepPreferredButEventuallyReachEveryFallback() {
        val entries=(0..30).toList()
        val seen=linkedSetOf<Int>()
        for(cursor in 0..29 step 7){
            val window=BsAutoSelectionPolicy.attempts(entries,cursor)
            assertEquals(8,window.size);assertEquals(0,window.first());assertEquals(8,window.distinct().size)
            seen.addAll(window)
        }
        assertEquals(entries.toSet(),seen)
        assertEquals(emptyList<Int>(),BsAutoSelectionPolicy.attempts(emptyList<Int>(),0))
    }
}
