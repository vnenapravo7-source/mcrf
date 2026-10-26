package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AssistedSteerProbeBindingTest {
    private fun logical()=ServerImport.Server(original="tuic://00000000-0000-4000-8000-000000000001:owned@192.0.2.1:443",name="Owned",protocol="tuic",host="192.0.2.1",port=443,
        outbound=JSONObject().put("type","tuic").put("server","192.0.2.1").put("server_port",443),endpoint=null,configuration=null,
        profileDns=listOf("192.0.2.53"))
    private fun effective(entry:ServerImport.Server,port:Int)=CompactProtocolPolicy.effective(entry,port,"a".repeat(32),"b".repeat(32))
    @Test fun exactOwnedIngressMatchesOriginalButRotatedPortCannotReuseProof(){
        val entry=logical();val first=SteerProbePlan.profile(effective(entry,10900))
        val binding=AssistedSteerProbeBinding(SteerProbePlan.fingerprint(entry),first)
        assertTrue(binding.matches(entry,first))
        assertFalse(binding.matches(entry,SteerProbePlan.profile(effective(entry,10901))))
        assertFalse(binding.matches(entry,entry.original))
    }
    @Test fun editedLogicalCredentialsInvalidateEvenUnchangedIngress(){
        val entry=logical();val ingress=SteerProbePlan.profile(effective(entry,10900))
        val binding=AssistedSteerProbeBinding(SteerProbePlan.fingerprint(entry),ingress)
        entry.outbound!!.put("password","changed-owned-value")
        assertFalse(binding.matches(entry,ingress))
    }
    @Test fun assistedRankingRemainsMarkedAndCapturesDnsWithoutPretendingNativeProtocolSupport(){
        val entry=logical();val pool=SteerAutomaticPool(listOf(entry),setOf(entry.id),1)
        assertFalse(SteerProbePlan.supports(entry));assertEquals(listOf(entry.id),pool.measurements.map {it.id})
        assertEquals(entry.profileDns,pool.measurements.single().profileDns)
        val binding=AssistedSteerProbeBinding(SteerProbePlan.fingerprint(entry),SteerProbePlan.profile(effective(entry,10900)))
        val bindings=mutableMapOf(entry.id to binding)
        val proof=SteerProbeDelays(mapOf(entry.id to 20L),mapOf(entry.id to 100L),"a".repeat(64),1,bindings)
        bindings.clear()
        assertSame(binding,proof.assisted.getValue(entry.id))
        assertEquals(listOf(entry.id),pool.rank(proof,101).map {it.id})
        assertTrue(pool.stillMarked(pool.candidates,listOf(entry),emptySet()).isEmpty())
    }
    @Test fun unrelatedAssistedEvidenceCannotBeInsertedIntoNativeResult(){
        val entry=logical();val binding=AssistedSteerProbeBinding(SteerProbePlan.fingerprint(entry),"owned-ingress")
        assertThrows(IllegalArgumentException::class.java){SteerProbeDelays(emptyMap(),emptyMap(),"a".repeat(64),1,mapOf(entry.id to binding))}
    }
    @Test fun explicitBulkScopeIncludesCompactWithoutClaimingNativeUriSupport(){
        val entry=logical()
        assertFalse(SteerProbePlan.supports(entry))
        assertEquals(listOf(entry.id),ProbePlan.all(listOf(entry),"steer").map {it.id})
        assertTrue(ProbePlan.all(listOf(entry),"steer").all {it.carrier==null})
    }
}
