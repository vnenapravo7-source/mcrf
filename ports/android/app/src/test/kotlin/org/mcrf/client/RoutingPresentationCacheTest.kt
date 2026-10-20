package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class RoutingPresentationCacheTest {
    @Test fun unchangedRevisionReturnsPreparedValueWithoutRebuilding(){
        val cache=RoutingPresentationCache<Any>();val prepared=Any()
        cache.put("disk1:1",prepared)
        repeat(10){assertSame(prepared,cache.get("disk1:1"))}
    }
    @Test fun savedRestoredOrExternallyChangedRevisionNeverReusesStaleValue(){
        val cache=RoutingPresentationCache<Any>();cache.put("disk1:1",Any())
        assertNull(cache.get("disk1:2"));assertNull(cache.get("restored:1"))
        cache.clear();assertNull(cache.get("disk1:1"))
    }
}
