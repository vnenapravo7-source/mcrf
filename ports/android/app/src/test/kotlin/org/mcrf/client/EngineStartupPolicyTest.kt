package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class EngineStartupPolicyTest {
    private val hash="a".repeat(64)
    private fun asset(kind:String,abi:String="arm64-v8a",digest:String=hash,size:Long=42)=JSONObject()
        .put("name",EngineModules.assetName(kind,abi)).put("state","uploaded").put("size",size).put("digest","sha256:$digest").put("label","MCRF Android; mcrf-version-code=5")
        .put("browser_download_url","https://github.com/${EnginePackage.REPOSITORY}/releases/download/${EnginePackage.CATALOGUE_RELEASE}/${EngineModules.assetName(kind,abi)}")
    private fun release(vararg assets:JSONObject)=JSONObject().put("tag_name",EnginePackage.CATALOGUE_RELEASE)
        .put("draft",false).put("prerelease",false).put("assets",JSONArray(assets.toList()))
    private fun local(digest:String=hash,size:Long=42)=EngineStartupPolicy.Installed(size,digest)
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
    @Test fun allRegisteredKindsAreCheckedIndependently(){
        val kinds=EngineModules.allKinds
        val data=JSONArray().put(release(*kinds.map {asset(it)}.toTypedArray()))
        val r=EngineStartupPolicy.discover(data,"arm64-v8a",kinds.associateWith {local("b".repeat(64))})
        assertEquals(kinds,r.updates.mapNotNull {it.engine}.toSet());assertTrue(r.unavailable.isEmpty())
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
