package org.mcrf.client

import org.json.JSONArray

/** Discovery compares installed bytes, never merely a tag or a previous download. */
object EngineStartupPolicy {
    data class Installed(val bytes:Long,val sha256:String,val versionCode:Long=0,
        val packageId:String?=null,val nativeAbis:Set<String> = emptySet())
    data class Result(val updates:List<EnginePackage>,val unavailable:Set<String>)
    data class AbiReplacement(val asset:EnginePackage,val installed:Installed) {
        val savedBytes:Long get()=installed.bytes-asset.bytes
    }

    fun abiNoticeEligible(appVersion:String,firstInstall:Long,lastUpdate:Long,noticedInstallation:Long):Boolean =
        (appVersion=="2.0" || appVersion.startsWith("2.0.") || appVersion.startsWith("2.0-")) &&
            firstInstall>0 && lastUpdate>firstInstall && noticedInstallation!=firstInstall

    fun abiReplacements(releases:JSONArray,abi:String,installed:Map<String,Installed>):List<AbiReplacement> {
        val stableCatalogue=JSONArray()
        for(i in 0 until releases.length())releases.optJSONObject(i)?.takeIf {
            EnginePackage.stableCatalogue(it.optString("tag_name")) && !it.optBoolean("prerelease")
        }?.let(stableCatalogue::put)
        return installed.toSortedMap().mapNotNull { (kind,local) ->
            if(kind !in EngineModules.allKinds || local.packageId != EngineModules.packageName(kind) ||
                local.nativeAbis.size<2 || abi !in local.nativeAbis)return@mapNotNull null
            val candidate=runCatching {EnginePackage.find(stableCatalogue,abi,kind)}.getOrNull()?:return@mapNotNull null
            if(candidate.engine!=kind || candidate.abi!=abi || candidate.versionCode==null ||
                candidate.versionCode<local.versionCode || candidate.bytes>=local.bytes)return@mapNotNull null
            AbiReplacement(candidate,local)
        }
    }
    // Old aggregate APK and a new dedicated module have unrelated version sequences.
    fun comparableVersion(dedicated:Boolean,version:Long)=if(dedicated)version else 0L
    /** An offer can outlive an external install/uninstall while the app remains open. */
    fun stillNeeded(offers:List<EnginePackage>,installed:Map<String,Installed>):List<EnginePackage> = offers.filter {asset->
        val kind=asset.engine
        val local=installed[kind]?:if(kind==BsEngineContract.KIND)installed.entries.firstOrNull{it.key in BsEngineContract.engines}?.value?.copy(versionCode=0)else null
        val remoteVersion=asset.versionCode
        kind in EngineModules.allKinds && local!=null && remoteVersion!=null && remoteVersion>=local.versionCode &&
            (asset.bytes!=local.bytes || asset.sha256!=local.sha256)
    }
    fun discover(releases:JSONArray,abi:String,installed:Map<String,Installed>,previewTag:String?=null):Result {
        val catalogue=JSONArray()
        for(i in 0 until releases.length())releases.getJSONObject(i).takeIf {
            EnginePackage.stableCatalogue(it.optString("tag_name")) ||
                it.optBoolean("prerelease")&&EnginePreviewChannel.allows(it.optString("tag_name"),previewTag,"sing-box")
        }?.let(catalogue::put)
        val updates=mutableListOf<EnginePackage>();val unavailable=linkedSetOf<String>()
        for((kind,local) in installed.toSortedMap()) {
            if(kind !in EngineModules.allKinds)continue
            try {
                val target=EnginePackage.find(catalogue,abi,kind,previewTag)
                // An installed universal APK of the same version needs no repeat download.
                val universal=runCatching {EnginePackage.findExact(catalogue,"universal",target.engine,previewTag)}.getOrNull()
                    ?.takeIf {it.versionCode!=null && it.versionCode==target.versionCode && it.version==target.version}
                val variants=listOfNotNull(target,universal)
                if(target.engine!=kind||variants.none {it.bytes==local.bytes&&it.sha256==local.sha256}) {
                    val remoteVersion=target.versionCode
                    if(remoteVersion==null)unavailable+=kind
                    else if(remoteVersion>=local.versionCode||(target.engine==BsEngineContract.KIND&&kind in BsEngineContract.engines))updates+=target
                }
            }catch(_:Exception){unavailable+=kind}
        }
        return Result(updates.distinctBy{it.url},unavailable)
    }
}
