package org.mcrf.client
import org.junit.Assert.*
import org.junit.Test

class RouteTextCacheTest {
    @Test fun unchangedContentIsParsedOnceButEditedContentIsNotReused(){
        val cache=RouteTextCache();var calls=0
        fun read(s:String)=cache.parse(s){calls++;mapOf("domain" to listOf(it))}
        assertEquals(read("one"),read("one"));assertEquals(1,calls)
        assertEquals(listOf("two"),read("two")["domain"]);assertEquals(2,calls)
    }
    @Test fun cachedCollectionsCannotBePoisonedByACaller(){
        val source=mutableListOf("safe");val cache=RouteTextCache()
        val result=cache.parse("a"){mapOf("domain" to source)};source.add("injected")
        assertEquals(listOf("safe"),result["domain"])
        try{(result["domain"] as MutableList).add("bad");fail()}catch(_:UnsupportedOperationException){}
    }
    @Test fun invalidOrOversizeEntriesNeverBecomeAnAdmissionToken(){
        var calls=0;val cache=RouteTextCache(1)
        repeat(2){cache.parse("a"){calls++;mapOf("domain" to listOf(it))}};assertEquals(2,calls)
        repeat(2){try{cache.parse("bad"){calls++;error("invalid")};fail()}catch(_:IllegalStateException){}}
        assertEquals(4,calls)
    }
    @Test fun leastRecentlyUsedEntriesAreEvicted(){
        val cache=RouteTextCache(maxEntries=1);var calls=0
        for(s in listOf("a","b","a"))cache.parse(s){calls++;emptyMap()}
        assertEquals(3,calls)
    }
}
