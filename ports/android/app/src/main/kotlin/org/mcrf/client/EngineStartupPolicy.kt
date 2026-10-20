package org.mcrf.client

import org.json.JSONArray

/** Discovery compares installed bytes, never merely a tag or a previous download. */
object EngineStartupPolicy {
    data class Installed(val bytes:Long,val sha256:String,val versionCode:Long=0)
    data class Result(val updates:List<EnginePackage>,val unavailable:Set<String>)
    // Old aggregate APK and a new dedicated module have unrelated version sequences.
    fun comparableVersion(dedicated:Boolean,version:Long)=if(dedicated)version else 0L
    /** An offer can outlive an external install/uninstall while the app remains open. */
    fun stillNeeded(offers:List<EnginePackage>,installed:Map<String,Installed>):List<EnginePackage> = offers.filter {asset->
        val kind=asset.engine
        val local=installed[kind] // No offer may turn an uninstalled module into a new install.
        val remoteVersion=asset.versionCode
        kind in EngineModules.allKinds && local!=null && remoteVersion!=null && remoteVersion>=local.versionCode &&
            (asset.bytes!=local.bytes || asset.sha256!=local.sha256)
    }
    fun discover(releases:JSONArray,abi:String,installed:Map<String,Installed>,previewTag:String?=null):Result {
        val catalogue=JSONArray()
        for(i in 0 until releases.length())releases.getJSONObject(i).takeIf {
            it.optString("tag_name")==EnginePackage.CATALOGUE_RELEASE ||
                it.optBoolean("prerelease")&&EnginePreviewChannel.allows(it.optString("tag_name"),previewTag,"sing-box")
        }?.let(catalogue::put)
        val updates=mutableListOf<EnginePackage>();val unavailable=linkedSetOf<String>()
        for((kind,local) in installed.toSortedMap()) {
            if(kind !in EngineModules.allKinds)continue
            try {
                val target=EnginePackage.find(catalogue,abi,kind,previewTag)
                val variants=listOfNotNull(target,runCatching {EnginePackage.find(catalogue,"universal",kind,previewTag)}.getOrNull())
                if(variants.none {it.bytes==local.bytes&&it.sha256==local.sha256}) {
                    val remoteVersion=target.versionCode
                    if(remoteVersion==null)unavailable+=kind
                    else if(remoteVersion>=local.versionCode)updates+=target
                }
            }catch(_:Exception){unavailable+=kind}
        }
        return Result(updates,unavailable)
    }
}
