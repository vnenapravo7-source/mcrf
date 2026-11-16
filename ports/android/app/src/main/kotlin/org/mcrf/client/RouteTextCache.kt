package org.mcrf.client

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Only pure text parsing is cached. No outbound, DNS, network or module admission is reused. */
class RouteTextCache(private val budget:Int=16*1024*1024,private val maxEntries:Int=16) {
    private data class Entry(val groups:Map<String,List<String>>,val bytes:Int)
    private val entries=LinkedHashMap<String,Entry>(16,.75f,true)
    private var bytes=0
    private fun cached(text:String):Map<String,List<String>>?=synchronized(this){entries[text]?.groups}
    fun parse(text:String,compile:(String)->Map<String,List<String>>):Map<String,List<String>> {
        synchronized(this){entries[text]?.let {return it.groups}}
        val result=compile(text)
        val immutable=Collections.unmodifiableMap(result.mapValues {Collections.unmodifiableList(ArrayList(it.value))})
        val cost=(text.length.toLong()*2+immutable.values.sumOf {list->list.sumOf {it.length.toLong()*2+48}}+256).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        if(cost>budget||Thread.currentThread().isInterrupted)return immutable
        synchronized(this){
            entries[text]?.let {return it.groups}
            while(entries.isNotEmpty()&&(bytes.toLong()+cost>budget||entries.size>=maxEntries)){
                val iterator=entries.entries.iterator();bytes-=iterator.next().value.bytes;iterator.remove()
            }
            if(maxEntries>0){entries[text]=Entry(immutable,cost);bytes+=cost}
        }
        return immutable
    }

    /** Opt-in only for one fixed PURE parser. The arbitrary-callback API above
     * remains non-coalesced; its entries cannot contaminate this private cache. */
    class FixedParser internal constructor(
        private val budget:Int,private val maxEntries:Int,
        private val compile:(String)->Map<String,List<String>>,
    ) {
        private class Flight(val owner:Thread,val bytes:Long) {
            val finished=CountDownLatch(1)
            @Volatile var result:Map<String,List<String>>?=null
        }
        private val cache=RouteTextCache(budget,maxEntries)
        private val lock=Any()
        private val pending=HashMap<String,Flight>()
        private var pendingBytes=0L
        private var waits=0L
        private var coalesced=0L
        private var waitNanos=0L

        fun parse(text:String):Map<String,List<String>> {
            cache.cached(text)?.let {return it}
            val thread=Thread.currentThread()
            var owned:Flight?=null
            val waiting=synchronized(lock) {
                cache.cached(text)?.let {return it}
                val existing=pending[text]
                when {
                    thread.isInterrupted -> null
                    existing!=null -> if(pending.values.any {it.owner===thread})null else existing
                    else -> {
                        val cost=text.length.toLong()*2+256
                        if(maxEntries>0&&pending.size<maxEntries&&cost<=budget.toLong()-pendingBytes) {
                            owned=Flight(thread,cost).also {pending[text]=it;pendingBytes+=cost}
                        }
                        null
                    }
                }
            }
            if(waiting!=null)return awaitOrCompile(text,waiting)
            val flight=owned?:return uncached(text)
            try {
                return cache.parse(text,compile).also {flight.result=it}
            }finally {
                synchronized(lock) {
                    if(pending[text]===flight){pending.remove(text);pendingBytes-=flight.bytes}
                }
                flight.finished.countDown()
            }
        }
        private fun uncached(text:String)=freeze(compile(text))
        private fun awaitOrCompile(text:String,flight:Flight):Map<String,List<String>> {
            val started=if(BuildConfig.DEBUG)System.nanoTime()else 0L
            var shared=false
            try {
                if(flight.finished.await(1000,TimeUnit.MILLISECONDS))flight.result?.let {shared=true;return it}
            }catch(_:InterruptedException) {
                Thread.currentThread().interrupt()
            }finally {traceWait(started,shared)}
            // Timeout, owner failure or interruption is not a new parse error.
            // Never publish this fallback or let it replace the owner's result.
            return uncached(text)
        }
        private fun traceWait(started:Long,shared:Boolean) {
            if(!BuildConfig.DEBUG)return
            runCatching {
                val elapsed=System.nanoTime()-started
                val summary=synchronized(lock) {
                    waits++;if(shared)coalesced++;waitNanos+=elapsed
                    if(waits<=8)"route_text.singleflight.waits=$waits coalesced=$coalesced wait_ms=${waitNanos/1_000_000L}"else null
                }
                if(summary!=null)android.util.Log.d("MCRF_STARTUP",summary)
            }
        }
    }
    companion object {
        fun singleFlight(budget:Int=16*1024*1024,maxEntries:Int=16,compile:(String)->Map<String,List<String>>)=
            FixedParser(budget,maxEntries,compile)
        private fun freeze(result:Map<String,List<String>>):Map<String,List<String>> =
            Collections.unmodifiableMap(result.mapValues {Collections.unmodifiableList(ArrayList(it.value))})
    }
}
