package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class EngineStartupPolicyTest {
    private val hash="a".repeat(64)
    private fun asset(kind:String,abi:String="arm64-v8a",digest:String=hash,size:Long=42,code:Long=5)=JSONObject()
        .put("name",EngineModules.assetName(kind,abi)).put("state","uploaded").put("size",size).put("digest","sha256:$digest").put("label","MCRF Android; mcrf-version-code=$code")
        .put("browser_download_url","https://github.com/${EnginePackage.REPOSITORY}/releases/download/${EnginePackage.CATALOGUE_RELEASE}/${EngineModules.assetName(kind,abi)}")
    private fun release(vararg assets:JSONObject)=JSONObject().put("tag_name",EnginePackage.CATALOGUE_RELEASE)
        .put("draft",false).put("prerelease",false).put("assets",JSONArray(assets.toList()))
    private fun local(digest:String=hash,size:Long=42)=EngineStartupPolicy.Installed(size,digest)
    @Test fun androidStableCatalogueReplacesLegacyCsqttAndQwdttWithOnePackage(){
        val tag=EnginePackage.ANDROID_STABLE_RELEASE
        val unified=asset("bs",code=34).put("browser_download_url",
            "https://github.com/${EnginePackage.REPOSITORY}/releases/download/$tag/${EngineModules.assetName("bs","arm64-v8a")}")
        val data=JSONArray().put(release(asset("csqtt"),asset("qwdtt")))
            .put(release(unified).put("tag_name",tag))
        val result=EngineStartupPolicy.discover(data,"arm64-v8a",mapOf("csqtt" to local(),"qwdtt" to local()))
        assertEquals(listOf("bs"),result.updates.map {it.engine})
        assertEquals(tag,result.updates.single().version)
    }
    @Test fun offersOnlyChangedInstalledAndNeverInstallsAbsent(){
        val data=JSONArray().put(release(asset("csqtt"),asset("wdtt"),asset("qwdtt")))
        val r=EngineStartupPolicy.discover(data,"arm64-v8a",mapOf("csqtt" to local("b".repeat(64)),"wdtt" to local()))
        assertEquals(listOf("csqtt"),r.updates.map {it.engine});assertTrue(r.unavailable.isEmpty())
    }
    @Test fun wholeInstalledApkHashNotVersionTagDeterminesCurrent(){
        val data=JSONArray().put(release(asset("qwdtt")))
        assertTrue(EngineStartupPolicy.discover(data,"arm64-v8a",mapOf("qwdtt" to local())).updates.isEmpty())
        assertEquals(1,EngineStartupPolicy.discover(data,"arm64-v8a",mapOf("qwdtt" to local("b".repeat(64)))).updates.size)
        assertEquals(1,EngineStartupPolicy.discover(data,"arm64-v8a",mapOf("qwdtt" to local(size=41))).updates.size)
    }
    @Test fun universalAlreadyCurrentMustNotBeRedownloaded(){
        val data=JSONArray().put(release(asset("wdtt"),asset("wdtt","universal","b".repeat(64),99)))
        assertTrue(EngineStartupPolicy.discover(data,"arm64-v8a",mapOf("wdtt" to local("b".repeat(64),99))).updates.isEmpty())
    }
    @Test fun installAndUpdatePreferEverySupportedDeviceAbi(){
        val deviceAbis=listOf("arm64-v8a","armeabi-v7a","x86_64")
        val rows=(listOf("universal")+deviceAbis).map {asset("wdtt",it)}
        val data=JSONArray().put(release(*rows.toTypedArray()))
        for(abi in deviceAbis){
            assertEquals(abi,EnginePackage.find(data,abi,"wdtt").abi)
            assertEquals(abi,EngineStartupPolicy.discover(data,abi,mapOf("wdtt" to local(size=1))).updates.single().abi)
        }
    }
    @Test fun missingExactAssetDoesNotOfferUniversal(){
        val data=JSONArray().put(release(asset("openflux","arm64-v8a"),asset("openflux","universal"),
            asset("steer","universal"),asset("wg-awg","universal")))
        assertThrows(EnginePackage.Unpublished::class.java){EnginePackage.find(data,"armeabi-v7a","openflux")}
        val result=EngineStartupPolicy.discover(data,"armeabi-v7a",mapOf("openflux" to local(size=1)))
        assertTrue(result.updates.isEmpty())
        assertEquals(setOf("openflux"),result.unavailable)
        assertThrows(EnginePackage.Unpublished::class.java){EnginePackage.find(data,"armeabi-v7a","steer")}
        assertThrows(EnginePackage.Unpublished::class.java){EnginePackage.find(data,"arm64-v8a","wg-awg")}
    }
    @Test fun badExactAssetCannotBeHiddenByUniversal(){
        val data=JSONArray().put(release(asset("openflux",digest="bad"),asset("openflux","universal")))
        assertThrows(IllegalArgumentException::class.java){EnginePackage.find(data,"arm64-v8a","openflux")}
        assertEquals(setOf("openflux"),EngineStartupPolicy.discover(data,"arm64-v8a",mapOf("openflux" to local(size=1))).unavailable)
    }
    @Test fun olderInstalledUniversalDoesNotHideAbiUpdate(){
        val data=JSONArray().put(release(asset("wdtt"),asset("wdtt","universal","b".repeat(64),99,code=4)))
        val installed=EngineStartupPolicy.Installed(99,"b".repeat(64),4)
        assertEquals("arm64-v8a",EngineStartupPolicy.discover(data,"arm64-v8a",mapOf("wdtt" to installed)).updates.single().abi)
    }
    @Test fun strictlyPrefersAbiEvenWhenUniversalHasHigherVersion(){
        val data=JSONArray().put(release(asset("wdtt"),asset("wdtt","universal",code=6)))
        assertEquals("arm64-v8a",EnginePackage.find(data,"arm64-v8a","wdtt").abi)
        val installed=EngineStartupPolicy.Installed(42,hash,5)
        val differentBytes=JSONArray().put(release(asset("wdtt"),asset("wdtt","universal","b".repeat(64),99,6)))
        assertTrue(EngineStartupPolicy.discover(differentBytes,"arm64-v8a",mapOf("wdtt" to installed)).updates.isEmpty())
        val newer=installed.copy(bytes=99,sha256="b".repeat(64),versionCode=6)
        assertTrue(EngineStartupPolicy.discover(differentBytes,"arm64-v8a",mapOf("wdtt" to newer)).updates.isEmpty())
    }
    @Test fun duplicateOrEncodedAssetNameCannotBeUsed(){
        val duplicated=JSONArray().put(release(asset("wdtt"),asset("wdtt"),asset("wdtt","universal")))
        assertThrows(IllegalArgumentException::class.java){EnginePackage.find(duplicated,"arm64-v8a","wdtt")}
        val encoded=asset("wdtt").apply {
            put("browser_download_url",getString("browser_download_url").replace("mcrf-engine-wdtt","mcrf-engine-%77dtt"))
        }
        assertThrows(IllegalArgumentException::class.java){EnginePackage.find(JSONArray().put(release(encoded)),"arm64-v8a","wdtt")}
    }
    @Test fun abiNoticeBelongsToAnUpgradedTwoPointZeroInstallation(){
        assertTrue(EngineStartupPolicy.abiNoticeEligible("2.0",10,20,-1))
        assertFalse(EngineStartupPolicy.abiNoticeEligible("2.0",10,10,-1))
        assertFalse(EngineStartupPolicy.abiNoticeEligible("0.7.3",10,20,-1))
        assertFalse(EngineStartupPolicy.abiNoticeEligible("2.0",10,30,10))
        assertTrue(EngineStartupPolicy.abiNoticeEligible("2.0.0-beta.10178",20,30,10))
    }
    @Test fun abiReplacementShowsOnlyVerifiedSmallerSamePackageCandidate(){
        val abi="arm64-v8a"
        val other="x86_64"
        val catalogue=JSONArray().put(release(asset("wdtt",abi,size=3_200_000),asset("wdtt", "universal",size=9_400_000)))
        val old=EngineStartupPolicy.Installed(9_400_000,"b".repeat(64),5,EngineModules.packageName("wdtt"),setOf(abi,other))
        fun find(value:EngineStartupPolicy.Installed)=EngineStartupPolicy.abiReplacements(catalogue,abi,mapOf("wdtt" to value))
        assertEquals(6_200_000L,find(old).single().savedBytes)
        assertEquals(abi,find(old).single().asset.abi)
        assertTrue(find(old.copy(nativeAbis=setOf(abi))).isEmpty())
        assertTrue(find(old.copy(packageId=EngineModules.LEGACY)).isEmpty())
        assertTrue(find(old.copy(nativeAbis=setOf(other,"armeabi-v7a"))).isEmpty())
        assertTrue(find(old.copy(versionCode=6)).isEmpty())
        assertTrue(find(old.copy(bytes=3_200_000)).isEmpty())
        val newerUniversal=JSONArray().put(release(asset("wdtt",abi),asset("wdtt","universal",code=6)))
        assertEquals(abi,EngineStartupPolicy.abiReplacements(newerUniversal,abi,mapOf("wdtt" to old)).single().asset.abi)
    }
    @Test fun allRegisteredKindsAreCheckedIndependently(){
        val kinds=EngineModules.allKinds
        val data=JSONArray().put(release(*kinds.map {asset(it)}.toTypedArray()))
        val r=EngineStartupPolicy.discover(data,"arm64-v8a",kinds.associateWith {local("b".repeat(64))})
        val physical=(kinds-BsEngineContract.engines)+BsEngineContract.KIND
        assertEquals(physical,r.updates.mapNotNull {it.engine}.toSet());assertTrue(r.unavailable.isEmpty())
    }
    @Test fun missingOrUnsupportedOneDoesNotHideOtherUpdates(){
        val data=JSONArray().put(release(asset("wdtt","armeabi-v7a"),asset("csqtt25")))
        val r=EngineStartupPolicy.discover(data,"armeabi-v7a",mapOf("wdtt" to local(size=1),"csqtt25" to local()))
        assertEquals(listOf("wdtt"),r.updates.map {it.engine});assertEquals(setOf("csqtt25"),r.unavailable)
    }
    @Test fun noFallbackToUnrelatedReleaseOrDraftOrBadDigest(){
        val unrelated=release(asset("csqtt")).put("tag_name","android-preview")
        val draft=release(asset("wdtt")).put("draft",true)
        val r=EngineStartupPolicy.discover(JSONArray().put(unrelated).put(draft),"arm64-v8a",mapOf("csqtt" to local(),"wdtt" to local()))
        assertTrue(r.updates.isEmpty());assertEquals(setOf("csqtt","wdtt"),r.unavailable)
        assertTrue(EngineStartupPolicy.discover(JSONArray().put(release(asset("csqtt",digest="bad"))),"arm64-v8a",mapOf("csqtt" to local())).updates.isEmpty())
    }
    @Test fun noInstalledModulesNoDownloadOrOffer(){
        val r=EngineStartupPolicy.discover(JSONArray().put(release(asset("wdtt"))),"arm64-v8a",emptyMap())
        assertTrue(r.updates.isEmpty());assertTrue(r.unavailable.isEmpty())
    }
    @Test fun newerLocalBuildIsNotDowngradedEvenWithDifferentSha(){
        val r=EngineStartupPolicy.discover(JSONArray().put(release(asset("wdtt"))),"arm64-v8a",mapOf("wdtt" to EngineStartupPolicy.Installed(41,"b".repeat(64),6)))
        assertTrue(r.updates.isEmpty());assertTrue(r.unavailable.isEmpty())
    }
    @Test fun unknownRemoteVersionIsNotCalledAnUpdate(){
        val r=EngineStartupPolicy.discover(JSONArray().put(release(asset("wdtt").removeLabel())),"arm64-v8a",mapOf("wdtt" to local(size=1)))
        assertTrue(r.updates.isEmpty());assertEquals(setOf("wdtt"),r.unavailable)
    }
    private fun JSONObject.removeLabel()=apply {remove("label")}
    @Test fun oldAggregateVersionDoesNotHideDedicatedUpgrade(){
        val version=EngineStartupPolicy.comparableVersion(false,100)
        assertEquals(0L,version)
        val r=EngineStartupPolicy.discover(JSONArray().put(release(asset("csqtt"))),"arm64-v8a",mapOf("csqtt" to EngineStartupPolicy.Installed(1,"b".repeat(64),version)))
        assertEquals(listOf("csqtt"),r.updates.map {it.engine})
        assertEquals(100L,EngineStartupPolicy.comparableVersion(true,100))
    }
}
