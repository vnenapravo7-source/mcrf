package org.mcrf.client

import org.json.JSONObject

/** Runtime rounds measure ONLY the compatible marked pool. Unmarked servers
 * cannot delay startup/failover or become candidates. The separate UI bulk
 * probe can still measure the compatible catalogue on explicit request. */
internal class SteerAutomaticPool(entries:List<ServerImport.Server>,marked:Set<String>,private val epoch:Long) {
    val measurements:List<ServerImport.Server> = java.util.Collections.unmodifiableList(entries.filter {entry->
        entry.id in marked&&compatible(entry)
    }.map {entry->ServerImport.Server(original=entry.original,name=entry.name,protocol=entry.protocol,host=entry.host,port=entry.port,
        outbound=entry.outbound?.let {JSONObject(it.toString())},endpoint=null,configuration=null,profileDns=entry.profileDns.toList())})
    private val marks=marked.toSet()
    val candidates:List<ServerImport.Server> get()=measurements.filter {it.id in marks}
    init {require(epoch>0&&measurements.size<=256&&measurements.map {it.id}.toSet().size==measurements.size)}
    fun rank(native:SteerProbeDelays,now:Long,failedId:String?=null):List<ServerImport.Server> {
        require(native.keys==measurements.map {it.id}.toSet()) {"STEER_AUTOMATIC_RESULT_SCOPE"}
        if(measurements.isEmpty())return emptyList()
        val session=SteerAutoSession(measurements.map {it.id},marks,epoch)
        try {
            measurements.forEach {entry->session.complete(epoch,entry.id,native.getValue(entry.id),native.completedAt.getValue(entry.id))}
            val fresh=session.candidates(now)
            // Prefer a different server after the live path failed, but allow
            // a genuinely NEW native success on the same server as the final
            // retry. This never resurrects its previous/generic cached proof.
            val ids=if(failedId==null)fresh else fresh.filter {it!=failedId}+fresh.filter {it==failedId}
            return ids.map {id->measurements.single {it.id==id}}
        }finally{session.stop()}
    }
    fun stillMarked(candidates:List<ServerImport.Server>,latest:List<ServerImport.Server>,currentMarks:Set<String>)=
        candidates.filter {entry->entry.id in currentMarks&&latest.any {it.id==entry.id&&SteerProbePlan.fingerprint(it)==SteerProbePlan.fingerprint(entry)}}
    companion object {
        fun compatible(entry:ServerImport.Server):Boolean = CompactProtocolPolicy.eligible(entry)||
            SteerProbePlan.supports(entry)&&try {SteerProbePlan.profile(entry);true}catch(_:Exception){false}
        fun better(candidateDelay:Long,currentDelay:Long,tolerance:Int)=candidateDelay>=0&&currentDelay>=0&&
            candidateDelay+tolerance.coerceIn(0,10000)<currentDelay
    }
}
