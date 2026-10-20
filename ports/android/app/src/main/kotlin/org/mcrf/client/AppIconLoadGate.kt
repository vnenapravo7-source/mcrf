package org.mcrf.client

/** Bound background work while rapidly scrolling/filtering an installed-app list. */
internal class AppIconLoadGate(private val capacity:Int=32) {
    init { require(capacity>0) }
    private val pending=HashSet<String>()
    @Synchronized fun reserve(id:String):Boolean {
        if(pending.size>=capacity)return false
        return pending.add(id)
    }
    @Synchronized fun release(id:String) { pending.remove(id) }
    @Synchronized fun clear() { pending.clear() }
}
