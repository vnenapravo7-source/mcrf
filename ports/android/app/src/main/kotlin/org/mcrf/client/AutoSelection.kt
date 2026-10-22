package org.mcrf.client

import org.json.JSONObject

/** Shared policy for native URL-test pools and the optional engine supervisor. */
object AutoSelection {
    const val LATENCY = "latency"
    const val BS_ENGINES = "bs-engines"
    @JvmField val ENGINE_PRIORITY = listOf("csqtt", "qwdtt", "wdtt", "openflux",S3BypassProfile.KIND)
    // URI import/display is not permission to run the excluded engine in a
    // background probe, startup pool or automatic failover.
    @JvmField val AUTOMATIC_EXCLUDED = setOf("openflux")
    fun prefer(candidateKind:String,candidateDelay:Long,currentKind:String,currentDelay:Long,tolerance:Int):Boolean {
        val candidateTier=ENGINE_PRIORITY.indexOf(candidateKind);val currentTier=ENGINE_PRIORITY.indexOf(currentKind)
        if(candidateKind in AUTOMATIC_EXCLUDED||candidateTier<0||currentTier<0||candidateDelay<0)return false
        if(currentDelay<0)return true
        return candidateTier<currentTier||(candidateTier==currentTier&&candidateDelay+tolerance.coerceIn(0,10000)<currentDelay)
    }
    /** Marks survive expired pings; actual startup still checks each candidate. */
    @JvmStatic fun bsStartup(entries:List<ServerImport.Server>,selected:Set<String>,probes:JSONObject,now:Long):List<ServerImport.Server> =
        startup(entries,selected,probes,now,BS_ENGINES)
    /** Admission is based on explicit marks, not the lifetime of a cached ping.
     * Fresh successes rank first; native URL-test/BS startup still verifies actual
     * connectivity. Expired/failed/absent measurements never erase the marked pool. */
    @JvmStatic @JvmOverloads fun startup(entries:List<ServerImport.Server>,selected:Set<String>,probes:JSONObject,now:Long,policy:String=LATENCY):List<ServerImport.Server> {
        require(policy==LATENCY||policy==BS_ENGINES) {"Неизвестный режим автовыбора."}
        fun freshDelay(entry:ServerImport.Server):Long {
            val probe=probes.optJSONObject(entry.id)?:return Long.MAX_VALUE
            val delay=probe.optLong("delay",-1)
            return if(delay>=0&&now-probe.optLong("at",0) in 0..300_000)delay else Long.MAX_VALUE
        }
        return entries.filter {it.id in selected&&eligible(it,policy)}.sortedWith(
            compareBy<ServerImport.Server> {if(policy==BS_ENGINES)ENGINE_PRIORITY.indexOf(it.protocol)else 0}
                .thenBy {freshDelay(it)})
    }
    @JvmStatic @JvmOverloads fun eligible(entry: ServerImport.Server, policy: String = LATENCY) = entry.protocol !in AUTOMATIC_EXCLUDED && entry.configuration == null &&
        if(policy == BS_ENGINES) entry.protocol in ENGINE_PRIORITY && entry.carrier != null else !entry.needsEngine()
    @JvmStatic @JvmOverloads fun proven(entries: List<ServerImport.Server>, selected: Set<String>, probes: JSONObject, now: Long, policy: String = LATENCY): List<ServerImport.Server> {
        require(policy == LATENCY || policy == BS_ENGINES) { "Неизвестный режим автовыбора." }
        val candidates = entries.filter { entry ->
            val result = probes.optJSONObject(entry.id)
            val permitted = eligible(entry,policy)
            permitted && entry.id in selected && result != null && result.optLong("delay", -1) >= 0 && now-result.optLong("at", 0) in 0..300_000
        }
        return if (policy == LATENCY) candidates else candidates.sortedWith(compareBy<ServerImport.Server> { ENGINE_PRIORITY.indexOf(it.protocol) }.thenBy { probes.getJSONObject(it.id).getLong("delay") })
    }
}
