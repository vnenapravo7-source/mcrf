package org.mcrf.client

/** Bounded control-plane state for Steer probes/failover, without another native core.
 * The P2 candidate executor reports native tunneled HTTPS results, never TCP reachability.
 * Source wiring is not admission: the legacy installed module has no probe
 * capability and stays refused until a fresh candidate passes affected tests.
 * No persisted generic/sing-box probe cache is accepted here. */
class SteerAutoSession(ids:List<String>,marked:Set<String>,private val generation:Long) {
    val measurementIds:List<String> = java.util.Collections.unmodifiableList(ids.toList())
    private val captured=measurementIds.toSet()
    private val selected=marked.intersect(captured)
    private data class Proof(val delay:Long,val at:Long)
    private val results=linkedMapOf<String,Proof>()
    private var closed=false
    init {
        require(generation>0&&ids.isNotEmpty()&&ids.size<=256&&captured.size==ids.size)
        require(ids.all {it.matches(Regex("[0-9a-f]{64}"))})
    }
    /** One completion per requested server and generation; failure is a measured result too. */
    @Synchronized fun complete(epoch:Long,id:String,delay:Long,completedAtElapsed:Long):Boolean {
        if(closed||epoch!=generation||id !in captured||id in results)return false
        require(delay>=-1&&completedAtElapsed>=0)
        results[id]=Proof(delay,completedAtElapsed)
        return true
    }
    @Synchronized fun completed():Int=results.size
    /** Select only explicitly marked, fresh, successful Steer tunnel results. */
    @Synchronized fun candidates(nowElapsed:Long):List<String> {
        require(nowElapsed>=0)
        if(closed)return emptyList()
        return selected.filter {id->results[id]?.let {it.delay>=0&&nowElapsed>=it.at&&nowElapsed-it.at<=300_000}==true}
            .sortedWith(compareBy<String> {results.getValue(it).delay}.thenBy {measurementIds.indexOf(it)})
    }
    /** A failed current health check must not immediately select the same old success again. */
    @Synchronized fun healthFailed(epoch:Long,id:String,atElapsed:Long):Boolean {
        if(closed||epoch!=generation||id !in captured)return false
        require(atElapsed>=0)
        val previous=results[id]
        if(previous!=null&&atElapsed<previous.at)return false
        results[id]=Proof(-1,atElapsed)
        return true
    }
    @Synchronized fun stop(){closed=true;results.clear()}
}
