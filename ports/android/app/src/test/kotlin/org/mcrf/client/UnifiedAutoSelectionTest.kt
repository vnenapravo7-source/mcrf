package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UnifiedAutoSelectionTest {
    private val now=1_000_000L
    private fun vpn(port:Int)=ServerImport.read("socks5://127.0.0.1:$port#VPN").single()
    private fun bs(kind:String,port:Int)=SwgProfile.fromJson("fixture",JSONObject()
        .put("type",kind).put("server","fixture.invalid").put("server_port",port)
        .put("password","fixture-only").put("hashes",JSONArray().put(java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32){1}))))
    private fun probe(entries:List<ServerImport.Server>,delays:List<Int>,at:Long=now)=JSONObject().apply {
        entries.forEachIndexed {index,entry->put(entry.id,JSONObject().put("delay",delays[index]).put("at",at))}
    }
    @Test fun fastestAndRecentUseTheSameEligibleVpnPoolWithoutMarks() {
        val slow=vpn(1080);val fast=vpn(1081);val entries=listOf(slow,fast)
        val probes=probe(entries,listOf(150,20))
        assertEquals(fast.id,BsAutoSelectionPolicy.startup(entries,probes,now,false,false,slow.id)!!.id)
        assertEquals(slow.id,BsAutoSelectionPolicy.startup(entries,probes,now,false,true,slow.id)!!.id)
        assertEquals(fast.id,BsAutoSelectionPolicy.startup(entries,probes,now,false,true,"removed")!!.id)
    }
    @Test fun bsHonorsProtocolPriorityAndNeverSelectsVpn() {
        val cs=bs("csqtt",46001);val wdtt=bs("wdtt",46002);val ordinary=vpn(1082)
        val entries=listOf(ordinary,wdtt,cs)
        val probes=probe(entries,listOf(1,5,100))
        assertEquals(cs.id,BsAutoSelectionPolicy.startup(entries,probes,now,true,false,"")!!.id)
        assertEquals(wdtt.id,BsAutoSelectionPolicy.startup(entries,probes,now,true,true,wdtt.id)!!.id)
        assertEquals(ordinary.id,BsAutoSelectionPolicy.startup(entries,probes,now,false,true,cs.id)!!.id)
    }
    @Test fun staleMeasurementsDoNotOutrankFreshAndEmptyPoolStaysEmpty() {
        val first=vpn(1083);val fresh=vpn(1084);val entries=listOf(first,fresh)
        val probes=probe(entries,listOf(1,50))
        probes.getJSONObject(first.id).put("at",now-300_001)
        assertEquals(fresh.id,BsAutoSelectionPolicy.startup(entries,probes,now,false,false,"")!!.id)
        assertNull(BsAutoSelectionPolicy.startup(emptyList(),probes,now,false,false,"missing"))
    }
}
