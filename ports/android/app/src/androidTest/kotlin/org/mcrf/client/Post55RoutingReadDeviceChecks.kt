package org.mcrf.client

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.io.File
import java.util.UUID
import java.util.function.Consumer

/** Deterministically interleave one owned write between route read and presentation. */
object Post55RoutingReadDeviceChecks {
    @JvmStatic fun run(base:Context,temporary:File,passed:Consumer<String>) {
        check(base.packageName=="org.mcrf.p3test") {"Isolated review UID required"}
        check(base.packageManager.getPackageInfo(base.packageName,0).longVersionCode>=10056) {"Old APK cannot verify post55 sources"}
        check(!RemoteServerOperationGate.busy())
        check(temporary.canonicalFile.parentFile==base.cacheDir.canonicalFile&&!temporary.exists()&&temporary.mkdir())
        val preferenceName="mcrf-post55-routing-${UUID.randomUUID()}"
        val preferences=base.getSharedPreferences(preferenceName,Context.MODE_PRIVATE)
        val replacement=RouteRules.defaults().put("defaultTarget","block")
        var injected=false
        lateinit var isolated:Context
        val interleaving=object:SharedPreferences by preferences {
            override fun getString(key:String,defValue:String?):String? {
                if(key=="dns"&&!injected){
                    // load() already read the empty file; the old implementation
                    // took its revision only AFTER this callback and stamped stale defaults.
                    injected=true
                    ProfileStore(isolated,"routing.enc").write(replacement.toString())
                }
                return preferences.getString(key,defValue)
            }
        }
        isolated=object:ContextWrapper(base) {
            override fun getFilesDir()=temporary
            override fun getApplicationContext():Context=this
            override fun getSharedPreferences(name:String,mode:Int):SharedPreferences {
                check(name=="MainActivity")
                return interleaving
            }
        }
        fun verify(condition:Boolean,message:String){check(condition){message};passed.accept(message)}
        try {
            val routes=RoutingStore(isolated)
            routes.load()
            verify(injected&&routes.snapshot().getString("defaultTarget")=="block",
                "A write during default-route parsing cannot stamp stale defaults with the newer disk revision")
            val (key,state)=routes.presentationSnapshot()
            verify(state.getString("defaultTarget")=="block"&&key.startsWith(ProfileStore(isolated,"routing.enc").revision()+":"),
                "Presentation key and document resolve the same committed routing data")
            val oldGeneration=routes.snapshotWithGeneration().second
            val restored=ProfileStore.snapshot(isolated)
            ProfileStore.replaceAll(isolated,restored)
            var edited=false
            var rejected=false
            try {routes.editInGeneration(oldGeneration){edited=true;it.put("globalDns",DnsConfig.defaults())}}
            catch(_:IllegalArgumentException){rejected=true}
            verify(rejected&&!edited,"A delayed DNS edit from the old generation is rejected before its mutation callback")
            verify(ProfileStore.snapshot(isolated)==restored,"Rejected old-generation DNS edit preserves the entire restored encrypted set")
        }finally {base.deleteSharedPreferences(preferenceName)}
    }
}
