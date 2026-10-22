package org.mcrf.client

import java.util.Collections

/** Only pure text parsing is cached. No outbound, DNS, network or module admission is reused. */
class RouteTextCache(private val budget:Int=16*1024*1024,private val maxEntries:Int=16) {
    private data class Entry(val groups:Map<String,List<String>>,val bytes:Int)
    private val entries=LinkedHashMap<String,Entry>(16,.75f,true)
    private var bytes=0
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
}
