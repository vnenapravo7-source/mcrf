package org.mcrf.client

import org.json.JSONObject

/** Explicit manual CONNECT, not the automatic marked-pool policy. Uses the pings shown in the list. */
object SectionConnectSelection {
    data class Start(val server:ServerImport.Server,val automatic:Boolean)
    /** An explicit CONNECT across pools must not be rejected by an unrelated marked pool. */
    fun start(entries:List<ServerImport.Server>,selectedId:String,whitelists:Boolean,automatic:Boolean,hasAutoCandidates:Boolean,probes:JSONObject):Start? {
        val current=entries.firstOrNull {it.id==selectedId&&ConnectionSections.belongs(it,whitelists)}
        val useAuto=automatic&&hasAutoCandidates&&current!=null
        val server=choose(entries,if(automatic&&!useAuto)""else selectedId,whitelists,probes)?:return null
        return Start(server,useAuto)
    }
    fun choose(entries:List<ServerImport.Server>,selectedId:String,whitelists:Boolean,probes:JSONObject):ServerImport.Server? {
        val pool=ConnectionSections.entries(entries,whitelists)
        pool.firstOrNull {it.id==selectedId}?.let {return it}
        // Missing measurements are not failed servers. If none succeeded, try an unmeasured
        // candidate before a failed one; equal pings preserve the visible catalogue order.
        return PingStatus.sorted(pool) {entry->probes.optJSONObject(entry.id)?.optLong("delay",-1)}.firstOrNull()
    }
}
