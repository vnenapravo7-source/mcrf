package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Source-only P2 cases, queued for the common batch. */
class SteerAutomaticPoolTest {
    private fun entry(name:String)=ServerImport.read("vless://4b72af9e-32b4-4b14-a2fc-4a9100d2eb21@$name.example.test:443?security=tls&sni=$name.example.test#$name").single()
    private fun native(values:Map<String,Long>,at:Map<String,Long>)=SteerProbeDelays(values,at,"a".repeat(64),7)
    @Test fun unmarkedServersNeitherDelayRuntimeRoundNorBecomeCandidates() {
        val one=entry("one");val two=entry("two");val three=entry("three")
        val pool=SteerAutomaticPool(listOf(one,two,three),setOf(two.id),1)
        assertEquals(listOf(two.id),pool.measurements.map {it.id})
        val results=native(mapOf(two.id to 44L),mapOf(two.id to 100L))
        assertEquals(listOf(two.id),pool.rank(results,100).map {it.id})
        assertThrows(IllegalArgumentException::class.java){pool.rank(native(mapOf(one.id to 1L,two.id to 44L),mapOf(one.id to 100L,two.id to 100L)),100)}
    }
    @Test fun keepsIndividualCompletionTimesAndRejectsWrongScopeOrStaleFutureProofs() {
        val one=entry("one");val two=entry("two");val pool=SteerAutomaticPool(listOf(one,two),setOf(one.id,two.id),2)
        val proof=native(mapOf(one.id to 1L,two.id to 2L),mapOf(one.id to 0L,two.id to 100L))
        assertEquals(listOf(two.id),pool.rank(proof,300001).map {it.id})
        assertTrue(pool.rank(proof,300101).isEmpty())
        assertThrows(IllegalArgumentException::class.java){pool.rank(proof,-1L)}
        assertThrows(IllegalArgumentException::class.java){pool.rank(native(mapOf(one.id to 1L),mapOf(one.id to 100L)),101)}
    }
    @Test fun failedLivePathPrefersAlternativeButFreshSameServerCanRecoverAsLastRetry() {
        val one=entry("one");val two=entry("two");val pool=SteerAutomaticPool(listOf(one,two),setOf(one.id,two.id),3)
        val at=mapOf(one.id to 100L,two.id to 100L)
        assertEquals(listOf(two.id,one.id),pool.rank(native(mapOf(one.id to 1L,two.id to 44L),at),101,one.id).map {it.id})
        assertEquals(listOf(one.id),pool.rank(native(mapOf(one.id to 1L,two.id to -1L),at),101,one.id).map {it.id})
    }
    @Test fun deletedUnmarkedOrEditedServerCannotWinACompletedRound() {
        val one=entry("one");val two=entry("two");val pool=SteerAutomaticPool(listOf(one,two),setOf(one.id,two.id),4)
        val ranking=pool.rank(native(mapOf(one.id to 1L,two.id to 2L),mapOf(one.id to 100L,two.id to 100L)),101)
        assertEquals(listOf(two.id),pool.stillMarked(ranking,listOf(two),setOf(one.id,two.id)).map {it.id})
        assertTrue(pool.stillMarked(ranking,listOf(one,two),emptySet()).isEmpty())
        val changed=ConnectionEdits.apply(one,ConnectionEdits.snapshot(one).apply {getJSONObject("outbound").put("server","changed.example.test")})
        assertEquals(listOf(two.id),pool.stillMarked(ranking,listOf(changed,two),setOf(one.id,two.id)).map {it.id})
    }
    @Test fun capturesJsonInputsAndNeverUsesAnEditedRawLinkOrCarrier() {
        val one=entry("one");val pool=SteerAutomaticPool(listOf(one),setOf(one.id),5)
        one.outbound!!.put("server","changed.example.test")
        assertEquals("one.example.test",pool.measurements.single().outbound!!.getString("server"))
        assertThrows(IllegalArgumentException::class.java){SteerProbePlan.profile(one)}
        val carrier=ServerImport.Server("owned-carrier","carrier","wdtt","",0,null,null,null,JSONObject().put("type","wdtt"))
        assertTrue(SteerAutomaticPool(listOf(one,carrier),setOf(one.id,carrier.id),6).measurements.isEmpty())
    }
    @Test fun hysteresisAndTypedProofsCannotAliasMutableMaps() {
        assertFalse(SteerAutomaticPool.better(44,50,10));assertTrue(SteerAutomaticPool.better(30,50,10))
        assertFalse(SteerAutomaticPool.better(44,-1,10));assertFalse(SteerAutomaticPool.better(-1,50,10))
        val one=entry("one");val values=mutableMapOf(one.id to 44L);val times=mutableMapOf(one.id to 100L)
        val proof=native(values,times);values.clear();times.clear()
        assertEquals(44L,proof.getValue(one.id));assertEquals(100L,proof.completedAt.getValue(one.id))
        assertThrows(IllegalArgumentException::class.java){native(mapOf(one.id to 1L),emptyMap())}
    }
}
