package org.mcrf.client

/** Discovery optimisation only. Installer acceptance always rehashes the APK. */
internal class EngineDiscoveryFingerprintCache(private val capacity:Int=64) {
    data class Identity(val packageName:String,val sourcePath:String,val bytes:Long,val modified:Long,val version:Long,val updated:Long)
    private val entries=LinkedHashMap<Identity,EngineStartupPolicy.Installed>(16,0.75f,true)
    init {require(capacity in 1..128)}
    @Synchronized fun read(identity:Identity,unchanged:()->Boolean,digest:()->EngineStartupPolicy.Installed):EngineStartupPolicy.Installed {
        val cached=entries[identity]
        if(cached!=null) {
            check(unchanged()) {"Engine package changed during discovery"}
            return cached
        }
        val value=digest()
        check(unchanged()) {"Engine package changed while calculating discovery fingerprint"}
        // One current identity per package. In-memory and bounded; no timers,
        // periodic file observers or persistence of APK paths/identities.
        entries.keys.removeAll {it.packageName==identity.packageName}
        entries[identity]=value
        while(entries.size>capacity)entries.remove(entries.keys.first())
        return value
    }
}
