package org.mcrf.client

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean

/** Real public release discovery/download/verification only. Never installs or selects a core. */
object PublishedEngineDeviceChecks {
    @JvmStatic fun run(context:Context, passed:java.util.function.Consumer<String>) {
        val requested=EngineModules.allKinds.filterNot {it in EngineModules.packetCores && ClientPrimarySelection.selected(context)!="steer"}.toSet()
        val assets=EnginePackages.published(requested.map {it as String?}.toSet())
        check(assets.size==requested.size&&assets.mapNotNull {it.engine}.toSet()==requested)
        passed.accept("Public GitHub release API resolves every supported arm64 module with exact ABI, size and SHA-256")
        for(asset in assets){
            val kind=requireNotNull(asset.engine)
            val file=EnginePackages.download(context,asset,{false},{})
            try {
                check(file.length()==asset.bytes)
                EnginePackages.verifyRequirements(context,file,setOf(kind))
                passed.accept("Public $kind APK downloaded and SHA/signer/package/interface/ABI verified; no installation or runtime claim")
            }finally{check(file.delete()){"Downloaded verification APK cleanup failed"}}
        }
    }
}
