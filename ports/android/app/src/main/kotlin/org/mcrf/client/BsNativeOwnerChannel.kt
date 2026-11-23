package org.mcrf.client

import org.json.JSONArray
import java.net.InetAddress
import java.util.Base64
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Prepared native-BS control channel. Not attached to a production child yet.
 * No hostname resolution, caller-supplied UID, or direct fallback on failure. */
internal class BsNativeOwnerChannel(
    private val generation:Long, private val live:()->Boolean, private val now:()->Long,
    private val resolve:(Query)->Owner, private val reply:(ByteArray)->Boolean
):AutoCloseable {
    data class Query(val generation:Long,val id:Long,val protocol:Int,val source:InetAddress,val sourcePort:Int,
                     val destination:InetAddress,val destinationPort:Int) {
        override fun toString()="BsOwnerQuery(redacted)"
    }
    class Owner(val uid:Int,packages:List<String>) {
        val packages:List<String> = java.util.Collections.unmodifiableList(packages.toList())
        init {require(uid>=0&&this.packages.size<=64&&this.packages.distinct().size==this.packages.size)
            require(this.packages.all {it.length in 1..255&&it.matches(Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*"))})
            require(this.packages.sumOf {it.length+3}+2<=11000)}
        override fun toString()="BsOwner(redacted)"
    }
    private val lock=Any();private var closed=false;private var highest=0L
    private val pending=hashSetOf<Long>()
    private val workers=ThreadPoolExecutor(2,2,0,TimeUnit.MILLISECONDS,ArrayBlockingQueue<Runnable>(30),
        {task->Thread(task,"mcrf-bs-owner").apply {isDaemon=true}})
    init {require(generation>0)}
    companion object {
        const val PREFIX="MCRF_BS_OWNER "
        private fun positive(value:String):Long {
            require(value.matches(Regex("[1-9][0-9]{0,18}")))
            return requireNotNull(value.toLongOrNull())
        }
        fun parse(line:String,generation:Long):Query {
            require(generation>0&&line.length<=384&&line.startsWith(PREFIX)&&line.all {it.code in 32..126})
            val fields=line.substring(PREFIX.length).split(' ');require(fields.size==7&&fields.none{it.isEmpty()})
            val epoch=positive(fields[0]);require(epoch==generation)
            val id=positive(fields[1]);val protocol=positive(fields[2]).toInt();require(fields[2] in setOf("6","17"))
            fun address(value:String)=requireNotNull(RouteRules.literal(value)).also {require('%' !in value)}
            fun port(value:String)=positive(value).also {require(it in 1..65535)}.toInt()
            val source=address(fields[3]);val destination=address(fields[5]);require(source.address.size==destination.address.size)
            return Query(epoch,id,protocol,source,port(fields[4]),destination,port(fields[6]))
        }
        fun answer(query:Query,owner:Owner?):ByteArray {
            val packages=owner?.let {Base64.getEncoder().encodeToString(JSONArray(it.packages).toString().toByteArray(Charsets.UTF_8))}.orEmpty()
            val value="BS_OWNER|${query.generation}|${query.id}|${owner?.uid?:-1}|$packages\n".toByteArray(Charsets.US_ASCII)
            require(value.size<=16384);return value
        }
        /** Only call while this app owns the exact live VPN generation. The
         * native request contains a tuple, never an asserted UID/package list. */
        fun androidLookup(context:android.content.Context,query:Query,live:()->Boolean):Owner {
            check(live())
            val manager=context.getSystemService(android.net.ConnectivityManager::class.java)
            val source=java.net.InetSocketAddress(query.source,query.sourcePort)
            val destination=java.net.InetSocketAddress(query.destination,query.destinationPort)
            val uid=manager.getConnectionOwnerUid(query.protocol,source,destination)
            require(uid>=0)
            val packages=context.packageManager.getPackagesForUid(uid)?.toList().orEmpty()
            require(manager.getConnectionOwnerUid(query.protocol,source,destination)==uid&&
                context.packageManager.getPackagesForUid(uid)?.toSet().orEmpty()==packages.toSet())
            check(live());return Owner(uid,packages)
        }
    }
    /** Called by one owned stdout reader: IDs must increase in wire order.
     * false means malformed/replayed/full/stopped; caller must fail the request,
     * not bypass its rule or retry it against another process. */
    fun offer(line:String):Boolean {
        val query=try{parse(line,generation)}catch(_:IllegalArgumentException){return false}
        val started=now();if(started<0)return false
        synchronized(lock){
            if(closed||!live()||query.id<=highest||pending.size>=32)return false
            pending+=query.id;highest=query.id
            try{workers.execute {perform(query,started)}}catch(_:java.util.concurrent.RejectedExecutionException){pending-=query.id;return false}
        }
        return true
    }
    private fun perform(query:Query,started:Long) {
        try {
            if(!current(query,started))return
            val owner=try{resolve(query)}catch(_:Exception){null}
            synchronized(lock){
                if(currentLocked(query,started)) {
                    // No platform lookup or blocking pipe write under this lock:
                    // reply is the existing bounded writer's offer operation.
                    if(!reply(answer(query,owner))){closed=true;pending.clear();workers.shutdownNow()}
                }
            }
        } finally {synchronized(lock){pending-=query.id}}
    }
    private fun currentLocked(query:Query,started:Long):Boolean {
        val current=now()
        return !closed&&query.id in pending&&live()&&current>=started&&current-started<2000
    }
    private fun current(query:Query,started:Long)=synchronized(lock){currentLocked(query,started)}
    override fun close(){synchronized(lock){closed=true;pending.clear();workers.shutdownNow()}}
    fun pendingRequests():Int=synchronized(lock){pending.size}
    fun awaitStopped(timeoutMs:Long):Boolean {require(timeoutMs in 0..5000);return workers.awaitTermination(timeoutMs,TimeUnit.MILLISECONDS)}
    override fun toString()="BsNativeOwnerChannel(redacted)"
}
