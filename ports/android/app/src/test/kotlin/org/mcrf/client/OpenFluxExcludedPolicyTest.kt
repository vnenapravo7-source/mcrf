package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Policy shapes only, no provider profile parsing/launch. */
class OpenFluxExcludedPolicyTest {
    private fun entry(protocol:String)=ServerImport.Server("owned-policy-$protocol",protocol,protocol,"192.0.2.1",443,
        null,null,null,JSONObject().put("type",protocol))
    @Test fun excludedEngineCannotEnterMarkedStartupOrMeasuredPool() {
        val excluded=entry("openflux");val supported=entry("csqtt")
        val entries=listOf(excluded,supported);val marks=entries.map {it.id}.toSet()
        val probes=JSONObject()
        entries.forEach {probes.put(it.id,JSONObject().put("delay",1).put("at",1000))}
        for(policy in listOf(AutoSelection.BS_ENGINES,AutoSelection.LATENCY)) {
            assertFalse(AutoSelection.eligible(excluded,policy))
            assertFalse(AutoSelection.startup(entries,marks,probes,1000,policy).any {it.id==excluded.id})
            assertFalse(AutoSelection.proven(entries,marks,probes,1000,policy).any {it.id==excluded.id})
        }
        assertEquals(listOf(supported.id),AutoSelection.bsStartup(entries,marks,probes,1000).map {it.id})
    }
    @Test fun excludedEngineCannotBeProbedOrBecomeFailoverEvenWithBetterLatency() {
        val entries=listOf(entry("openflux"),entry("csqtt"))
        assertEquals(listOf(entries[1].id),ProbePlan.all(entries).map {it.id})
        for(policy in listOf(AutoSelection.BS_ENGINES,AutoSelection.LATENCY))
            assertEquals(listOf(entries[1].id),ProbePlan.automatic(entries,policy).map {it.id})
        assertFalse(AutoSelection.prefer("openflux",1,"wdtt",-1,0))
        assertFalse(AutoSelection.prefer("openflux",1,"openflux",1000,0))
    }
}
