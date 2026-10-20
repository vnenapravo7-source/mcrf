package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Only new release-channel coverage. Existing P1/network suites are retained, not rerun. */
class Preview21ReleaseTest {
    private val tag="android-v0.1.0-preview.20261020.1"
    private fun asset(kind:String,code:Int,abi:String="arm64-v8a")=JSONObject()
        .put("name",EngineModules.assetName(kind,abi)).put("state","uploaded").put("size",42)
        .put("digest","sha256:"+"a".repeat(64)).put("label","MCRF; mcrf-version-code=$code")
        .put("browser_download_url","https://github.com/${EnginePackage.REPOSITORY}/releases/download/$tag/${EngineModules.assetName(kind,abi)}")
    private fun releases(draft:Boolean=false)=JSONArray().put(JSONObject().put("tag_name",tag)
        .put("draft",draft).put("prerelease",true)
        .put("assets",JSONArray(listOf(asset("sing-box",7),asset("steer",6),asset(WgAddonPolicy.KIND,3)))))
    @Test fun exactNewPreviewOffersAllThreeOwnedModules() {
        for((kind,code) in mapOf("sing-box" to 7L,"steer" to 6L,WgAddonPolicy.KIND to 3L)) {
            assertEquals(code,EnginePackage.find(releases(),"arm64-v8a",kind,tag).versionCode)
        }
    }
    @Test fun unrelatedPreviewOrMissingOptInCannotOfferTheseCandidates() {
        for(kind in listOf("sing-box","steer",WgAddonPolicy.KIND)) {
            assertFalse(EnginePreviewChannel.allows(tag,null,kind))
            assertFalse(EnginePreviewChannel.allows(tag,"android-v0.1.0-preview.20261019.2",kind))
        }
    }
    @Test fun unrelatedEnginesRemainOnStableCatalogue() {
        for(kind in listOf("wdtt","qwdtt","csqtt","csqtt25","openflux",null))
            assertFalse(EnginePreviewChannel.allows(tag,tag,kind))
    }
    @Test fun absentModulesAreNotAutomaticallyInstalled() {
        assertTrue(EngineStartupPolicy.discover(releases(),"arm64-v8a",emptyMap(),tag).updates.isEmpty())
    }
    @Test fun installedModulesUpdateWithoutDowngradingNewerOnes() {
        val old=mapOf("steer" to EngineStartupPolicy.Installed(41,"b".repeat(64),5),
            WgAddonPolicy.KIND to EngineStartupPolicy.Installed(41,"b".repeat(64),2))
        assertEquals(setOf("steer",WgAddonPolicy.KIND),EngineStartupPolicy.discover(releases(),"arm64-v8a",old,tag).updates.map {it.engine}.toSet())
        val newer=old.mapValues {EngineStartupPolicy.Installed(41,"b".repeat(64),99)}
        assertTrue(EngineStartupPolicy.discover(releases(),"arm64-v8a",newer,tag).updates.isEmpty())
    }
    @Test fun currentBytesAndDraftAreNotUpdates() {
        val current=mapOf("steer" to EngineStartupPolicy.Installed(42,"a".repeat(64),6))
        assertTrue(EngineStartupPolicy.discover(releases(),"arm64-v8a",current,tag).updates.isEmpty())
        assertTrue(EngineStartupPolicy.discover(releases(true),"arm64-v8a",current,tag).updates.isEmpty())
    }
    @Test fun newVersionIsDiscoverableAfterTwenty() {
        assertTrue(AppUpdatePresentation.newer(tag,"0.1.0-preview.20261019.2"))
        assertFalse(AppUpdatePresentation.newer(tag,"0.1.0-preview.20261020.1"))
    }
}
