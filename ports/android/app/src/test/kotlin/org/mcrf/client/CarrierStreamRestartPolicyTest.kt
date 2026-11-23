package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Authored for the common P0 gate; no Android connection or provider operations. */
class CarrierStreamRestartPolicyTest {
    @Test fun blockedOutcomeUsesOnlyTrustedLimitExplanation() {
        for(kind in VkCredentialLimit.Kind.values()) {
            val limit=VkCredentialLimit(kind,600)
            assertEquals(limit.explanation(),CarrierStreamRestartPolicy.blockedExplanation(VkCredentialLimit.Blocked(limit)))
        }
        assertEquals(VkCredentialLimit.NETWORK_MESSAGE,CarrierStreamRestartPolicy.blockedExplanation(
            VkCredentialLimit.Blocked(VkCredentialLimit(VkCredentialLimit.Kind.FLOOD,600))))
        assertNull(CarrierStreamRestartPolicy.blockedExplanation(java.io.IOException("untrusted-provider-token")))
    }
    @Test fun disconnectedForeignStoppedAndStaleOwnersCannotReconnect() {
        fun admitted(requested:Long=7,current:Long=7,owner:Boolean=true,running:Boolean=true,
            wanted:Boolean=true,vpn:Boolean=true,tun:Boolean=true,policy:Boolean=true)=
            CarrierStreamRestartPolicy.owned(requested,current,owner,running,wanted,vpn,tun,policy)
        assertTrue(admitted())
        assertFalse(admitted(owner=false));assertFalse(admitted(running=false))
        assertFalse(admitted(wanted=false));assertFalse(admitted(tun=false));assertFalse(admitted(policy=false))
        assertFalse(admitted(current=8));assertFalse(admitted(requested=6))
        assertTrue(admitted(vpn=false,tun=false)) // An already running owned proxy needs no TUN.
    }

    @Test fun latestSliderValuesCoalesceAcrossKindsAndStaleCompletionCannotEraseThem() {
        val pending=CarrierStreamRestartPolicy.Pending()
        val first=pending.offer(5,mapOf("csqtt" to 18))
        val second=pending.offer(5,mapOf("wdtt" to 36))
        val last=pending.offer(5,mapOf("csqtt" to 72))
        assertNull(pending.snapshot(first,5));assertNull(pending.snapshot(second,5))
        assertEquals(mapOf("csqtt" to 72,"wdtt" to 36),pending.snapshot(last,5))
        pending.complete(first)
        assertEquals(mapOf("csqtt" to 72,"wdtt" to 36),pending.snapshot(last,5))
        pending.complete(last)
        assertTrue(pending.snapshot(last,5)!!.isEmpty())
    }

    @Test fun stopAndNewSessionInvalidateQueuedWorkWithoutResurrectingOldChanges() {
        val pending=CarrierStreamRestartPolicy.Pending()
        val old=pending.offer(10,mapOf("qwdtt" to 27))
        assertNull(pending.snapshot(old,11))
        val next=pending.offer(12,mapOf("wdtt" to 54))
        pending.complete(old)
        assertEquals(mapOf("wdtt" to 54),pending.snapshot(next,12))
        assertNull(pending.snapshot(old,12))
    }

    @Test fun rapidSameKindChangesRemainOneLatestValueNotAProcessStartQueue() {
        val pending=CarrierStreamRestartPolicy.Pending()
        var last=pending.offer(1,mapOf("csqtt25" to 10))
        repeat(500){index->last=pending.offer(1,mapOf("csqtt25" to (index%10+1)*10))}
        assertEquals(mapOf("csqtt25" to 100),pending.snapshot(last,1))
        assertThrows(IllegalArgumentException::class.java){pending.offer(1,mapOf("other" to 20))}
    }

    @Test fun onlyChangedGlobalConsumersWithDifferentEffectiveCountsAreAffected() {
        for(kind in CarrierStreamDefaults.kinds) {
            assertTrue(CarrierStreamRestartPolicy.affected(setOf(kind),kind,false,18,36))
            assertFalse(CarrierStreamRestartPolicy.affected(setOf(kind),kind,true,18,36))
            assertFalse(CarrierStreamRestartPolicy.affected(setOf(kind),kind,false,27,27))
            assertFalse(CarrierStreamRestartPolicy.affected(emptySet(),kind,false,18,36))
        }
        assertFalse(CarrierStreamRestartPolicy.affected(setOf("csqtt"),"qwdtt",false,18,36))
        assertFalse(CarrierStreamRestartPolicy.affected(setOf("csqtt"),"csqtt25",false,10,20))
        assertFalse(CarrierStreamRestartPolicy.affected(setOf("other"),"other",false,18,36))
    }

    private fun legacy()=JSONObject().put("type","csqtt").put("hashes",JSONArray().put("owned-call"))

    @Test fun childSnapshotUsesItsActualCountDespiteDefaultsChangingDuringStart() {
        val raw=legacy()
        val effective=CarrierStreamDefaults.apply(raw,JSONObject().put("csqtt",18))
        val snapshot=CarrierStreamRestartPolicy.startedProfile(effective,raw)
        effective.put("workers",27)
        val latest=JSONObject().put("csqtt",27)
        assertEquals(18,snapshot.getInt("workers"))
        assertTrue(CarrierStreamRestartPolicy.affected(setOf("csqtt"),"csqtt",false,
            snapshot.getInt("workers"),CarrierStreamDefaults.apply(snapshot,latest).getInt("workers")))
    }

    @Test fun childSnapshotRetainsServerOverrideRemovedFromNativeInput() {
        val raw=legacy().put(CarrierStreamDefaults.OVERRIDE,18)
        val effective=CarrierStreamDefaults.apply(raw,JSONObject().put("csqtt",27))
        assertFalse(effective.has(CarrierStreamDefaults.OVERRIDE))
        val snapshot=CarrierStreamRestartPolicy.startedProfile(effective,raw)
        assertEquals(18,snapshot.getInt(CarrierStreamDefaults.OVERRIDE))
        assertEquals(18,CarrierStreamDefaults.apply(snapshot,JSONObject().put("csqtt",9)).getInt("workers"))
    }

    @Test fun callCapsAndPerServerOverridesMakeSomeSavedChangesRuntimeNoOps() {
        val profile=legacy()
        val before=CarrierStreamDefaults.apply(profile,JSONObject().put("csqtt",36)).getInt("workers")
        val after=CarrierStreamDefaults.apply(profile,JSONObject().put("csqtt",126)).getInt("workers")
        assertEquals(27,before);assertEquals(before,after)
        assertFalse(CarrierStreamRestartPolicy.affected(setOf("csqtt"),"csqtt",false,before,after))
        profile.put(CarrierStreamDefaults.OVERRIDE,18)
        assertEquals(18,CarrierStreamDefaults.apply(profile,JSONObject().put("csqtt",126)).getInt("workers"))
        assertEquals(18,profile.getInt(CarrierStreamDefaults.OVERRIDE))
    }

    @Test fun modernExtendedWorkersChangeWhileOldImplementationCallCapDoesNot() {
        val profile=legacy().put("csqtt_generation","2.5").put("csqtt_implementation","swg-panel-2.5.3")
        val low=JSONObject().put("csqtt25",10)
        val high=JSONObject().put("csqtt25",100)
        fun count(settings:JSONObject)=CarrierStreamDefaults.apply(profile,settings).getInt("workers")
        assertTrue(CarrierStreamRestartPolicy.affected(setOf("csqtt25"),"csqtt25",false,count(low),count(high)))
        profile.put("csqtt_implementation","swg-panel-2.5.1")
        assertEquals(10,count(low));assertEquals(10,count(high))
        assertFalse(CarrierStreamRestartPolicy.affected(setOf("csqtt25"),"csqtt25",false,count(low),count(high)))
    }
}
