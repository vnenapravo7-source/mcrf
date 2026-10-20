package org.mcrf.client

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class EnginePreviewChannelTest {
    private val tag="android-v0.1.0-preview.20261019.2"
    private val sha7="a".repeat(64)
    private fun asset(t:String,kind:String="sing-box",abi:String="arm64-v8a",code:Int=7,sha:String=sha7,size:Long=42)=JSONObject()
        .put("name",EngineModules.assetName(kind,abi)).put("state","uploaded").put("size",size).put("digest","sha256:$sha")
        .put("label","MCRF; mcrf-version-code=$code").put("browser_download_url","https://github.com/${EnginePackage.REPOSITORY}/releases/download/$t/${EngineModules.assetName(kind,abi)}")
    private fun release(t:String,vararg a:JSONObject)=JSONObject().put("tag_name",t).put("draft",false)
        .put("prerelease",t!=EnginePackage.CATALOGUE_RELEASE).put("assets",JSONArray(a.toList()))
    private fun data()=JSONArray().put(release(EnginePackage.CATALOGUE_RELEASE,asset(EnginePackage.CATALOGUE_RELEASE,code=6,sha="b".repeat(64))))
        .put(release(tag,asset(tag),asset(tag,abi="universal",sha="c".repeat(64),size=99)))
    private fun installed(code:Long=6,sha:String="b".repeat(64),size:Long=41)=mapOf("sing-box" to EngineStartupPolicy.Installed(size,sha,code))
    @Test fun exactAppPreviewWinsOnlyWithExplicitChannel() {
        assertEquals(tag,EnginePackage.find(data(),"arm64-v8a","sing-box",tag).version)
        assertEquals(EnginePackage.CATALOGUE_RELEASE,EnginePackage.find(data(),"arm64-v8a","sing-box").version)
    }
    @Test fun startupOffersSixToSevenWithoutChangingStableCatalogue() {
        val result=EngineStartupPolicy.discover(data(),"arm64-v8a",installed(),tag)
        assertEquals(7L,result.updates.single().versionCode)
        assertEquals(tag,result.updates.single().version);assertTrue(result.unavailable.isEmpty())
    }
    @Test fun currentOrNewerModuleIsNeverRedownloadedOrDowngraded() {
        assertTrue(EngineStartupPolicy.discover(data(),"arm64-v8a",installed(7,sha7,42),tag).updates.isEmpty())
        assertTrue(EngineStartupPolicy.discover(data(),"arm64-v8a",installed(8),tag).updates.isEmpty())
    }
    @Test fun universalAlreadyCurrentIsRecognized() {
        assertTrue(EngineStartupPolicy.discover(data(),"arm64-v8a",installed(7,"c".repeat(64),99),tag).updates.isEmpty())
    }
    @Test fun unrelatedNewerPreviewAndExactDraftAreNotTrusted() {
        val other="android-v0.1.0-preview.20261020.99"
        val releases=JSONArray().put(release(other,asset(other,code=99))).put(release(tag,asset(tag)).put("draft",true))
            .put(release(EnginePackage.CATALOGUE_RELEASE,asset(EnginePackage.CATALOGUE_RELEASE,code=6)))
        assertEquals(6L,EnginePackage.find(releases,"arm64-v8a","sing-box",tag).versionCode)
    }
    @Test fun previewDoesNotReplaceOtherEnginesOrInstallAbsentModules() {
        val releases=JSONArray().put(release(tag,asset(tag,"wdtt",code=99)))
            .put(release(EnginePackage.CATALOGUE_RELEASE,asset(EnginePackage.CATALOGUE_RELEASE,"wdtt",code=5)))
        assertEquals(5L,EnginePackage.find(releases,"arm64-v8a","wdtt",tag).versionCode)
        assertTrue(EngineStartupPolicy.discover(data(),"arm64-v8a",emptyMap(),tag).updates.isEmpty())
    }
    @Test fun invalidHashOrMissingVersionDoesNotBecomeUpdate() {
        val invalid=JSONArray().put(release(tag,asset(tag,sha="bad")))
        assertTrue(EngineStartupPolicy.discover(invalid,"arm64-v8a",installed(),tag).updates.isEmpty())
        val missing=asset(tag).apply {remove("label")}
        val result=EngineStartupPolicy.discover(JSONArray().put(release(tag,missing)),"arm64-v8a",installed(),tag)
        assertTrue(result.updates.isEmpty());assertEquals(setOf("sing-box"),result.unavailable)
    }
    @Test fun stableOrMalformedBuildCannotOptIntoPreview() {
        assertNull(EnginePreviewChannel.tag("1.0.0"));assertNull(EnginePreviewChannel.tag("0.1.0-preview/evil"))
        assertFalse(EnginePreviewChannel.allows(tag,null,"sing-box"))
        assertEquals(tag,EnginePreviewChannel.tag("0.1.0-preview.20261019.2"))
    }
    @Test fun releaseVersionIsNewForOldAppButNotForInstalledNewApp() {
        assertTrue(AppUpdatePresentation.newer(tag,"0.1.0-preview.20261019.1"))
        assertFalse(AppUpdatePresentation.newer(tag,"0.1.0-preview.20261019.2"))
    }
    @Test fun actualStartupAndManualDiscoveryUseExactAppChannel() {
        fun source(name:String)=listOf(File("src/main/kotlin/org/mcrf/client/$name.kt"),File("app/src/main/kotlin/org/mcrf/client/$name.kt")).first{it.isFile}.readText()
        val startup=source("EngineStartupUpdates")
        assertTrue(startup.contains("EngineStartupPolicy.discover(catalogue(),abi,installed,EnginePreviewChannel.tag(BuildConfig.VERSION_NAME))"))
        assertFalse(startup.contains("/releases/tags/"));assertTrue(startup.contains("/releases?per_page=30"))
        assertTrue(source("EnginePackages").contains("EnginePackage.find(releases,abi,it,EnginePreviewChannel.tag(BuildConfig.VERSION_NAME))"))
        assertTrue(startup.contains("setNegativeButton(\"Позже\""));assertTrue(startup.contains("activity.updateInstalledEngines(assets)"))
    }
}
