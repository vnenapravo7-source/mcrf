package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** New42–45 batch: queued until all P0 sources are ready; not run separately. */
class AutoStartupPoolTest {
    private fun entry(id:String,bs:Boolean=false)=ServerImport.Server(id,id,if(bs)"csqtt"else"vless","localhost",1,null,null,null,if(bs)JSONObject().put("type","csqtt")else null)
    private val a=entry("a");private val b=entry("b");private val bs=entry("bs",true)
    private val now=1_000_000L
    @Test fun markedServersRemainCandidatesWhenAllCachedPingsExpire() {
        val probes=JSONObject().put(a.id,JSONObject().put("delay",10).put("at",now-300_001))
        assertTrue(AutoSelection.proven(listOf(a),setOf(a.id),probes,now).isEmpty())
        assertEquals(listOf(a),AutoSelection.startup(listOf(a),setOf(a.id),probes,now))
    }
    @Test fun absentOrFailedPingIsNotAnEmptySelection() {
        val probes=JSONObject().put(a.id,JSONObject().put("delay",-1).put("at",now))
        assertEquals(listOf(a,b),AutoSelection.startup(listOf(a,b),setOf(a.id,b.id),probes,now))
    }
    @Test fun freshMeasuredCandidateRanksBeforeStaleWithoutMutatingMarks() {
        val marks=setOf(a.id,b.id);val probes=JSONObject().put(a.id,JSONObject().put("delay",1).put("at",now-400_000))
            .put(b.id,JSONObject().put("delay",70).put("at",now))
        val before=probes.toString()
        assertEquals(listOf(b,a),AutoSelection.startup(listOf(a,b),marks,probes,now))
        assertEquals(before,probes.toString());assertEquals(setOf(a.id,b.id),marks)
    }
    @Test fun poolStillExcludesUnmarkedAndOppositeFamily() {
        assertTrue(AutoSelection.startup(listOf(a,b,bs),emptySet(),JSONObject(),now).isEmpty())
        assertEquals(listOf(a),AutoSelection.startup(listOf(a,b,bs),setOf(a.id,bs.id),JSONObject(),now))
        assertEquals(listOf(bs),AutoSelection.startup(listOf(a,b,bs),setOf(a.id,bs.id),JSONObject(),now,AutoSelection.BS_ENGINES))
    }
}
