package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class RoutingScreenCacheTest {
    @Test fun unchangedNavigationReturnsExactRenderedScreen() {
        val cache=RoutingScreenCache<Any>();val screen=Any()
        cache.put(this,"revision-1",true,screen)
        repeat(10){assertSame(screen,cache.get(this,"revision-1",true))}
    }
    @Test fun editsAndThemeChangesNeverReuseStaleControls() {
        val cache=RoutingScreenCache<Any>();val old=Any();cache.put(this,"revision-1",true,old)
        assertNull(cache.get(this,"revision-2",true));assertNull(cache.get(this,"revision-1",false))
        val fresh=Any();cache.put(this,"revision-2",false,fresh)
        assertSame(fresh,cache.get(this,"revision-2",false));assertNull(cache.get(this,"revision-1",true))
    }
    @Test fun screenCacheIsActivityLocalAndExplicitlyClearable() {
        val first=RoutingScreenCache<Any>();val second=RoutingScreenCache<Any>()
        first.put(this,"same",true,Any());assertNull(second.get(this,"same",true))
        assertNull(first.get(Any(),"same",true))
        first.clear();assertNull(first.get(this,"same",true))
    }
}
