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
    @Test fun reservedReleaseTagsCannotMasqueradeAsStable() {
        for(preview in listOf(tag,MobileBetaUpdates.TAG)) {
            val misplaced=release(preview,asset(preview,code=99)).put("prerelease",false)
            val releases=JSONArray().put(misplaced)
                .put(release(EnginePackage.CATALOGUE_RELEASE,asset(EnginePackage.CATALOGUE_RELEASE,code=6)))
            assertEquals(6L,EnginePackage.find(releases,"arm64-v8a","sing-box").versionCode)
            assertEquals(6L,EnginePackage.find(releases,"arm64-v8a","sing-box",preview).versionCode)
        }
    }
    @Test fun disablingPreviewKeepsNewerInstalledEngineUntilStableCatchesUp() {
        val betaInstalled=installed(7,sha7,42)
        assertTrue(EngineStartupPolicy.discover(data(),"arm64-v8a",betaInstalled).updates.isEmpty())
        val stableUpdate=JSONArray().put(release(EnginePackage.CATALOGUE_RELEASE,
            asset(EnginePackage.CATALOGUE_RELEASE,code=8,sha="d".repeat(64))))
        assertEquals(8L,EngineStartupPolicy.discover(stableUpdate,"arm64-v8a",betaInstalled).updates.single().versionCode)
    }
    @Test fun optedInMobileBetaUsesNewestSemanticReleaseForEachKindAndAbi() {
        val pinned=MobileBetaUpdates.TAG
        val latest="android-v2.10.0-beta.9"
        val earlier="android-v2.9.0-beta.99999"
        val catalogue=EnginePackage.CATALOGUE_RELEASE
        val releases=JSONArray().put(release(earlier,asset(earlier,code=9)))
            .put(release(catalogue,asset(catalogue,code=6),asset(catalogue,"tgws",code=4)))
            .put(release(pinned,asset(pinned,code=7),asset(pinned,"wdtt",code=4)))
            .put(release(latest,asset(latest,code=9),asset(latest,"wdtt",abi="x86_64",code=5)))
        assertEquals(latest,EnginePackage.find(releases,"arm64-v8a","sing-box",pinned).version)
        assertEquals(latest,EngineStartupPolicy.discover(releases,"arm64-v8a",installed(),pinned).updates.single().version)
        assertEquals(pinned,EnginePackage.find(releases,"arm64-v8a","wdtt",pinned).version)
        assertEquals(catalogue,EnginePackage.find(releases,"arm64-v8a","tgws",pinned).version)
        assertEquals(catalogue,EnginePackage.find(releases,"arm64-v8a","sing-box").version)
    }
    @Test fun invalidFutureBetaFallsBackAndLowerEngineCodeCannotMaskUpgrade() {
        val pinned=MobileBetaUpdates.TAG
        val latest="android-v2.0.1-beta.1"
        val invalid="android-v2.0.2-beta.1"
        val draft="android-v2.0.3-beta.1"
        val catalogue=EnginePackage.CATALOGUE_RELEASE
        val releases=JSONArray().put(release(pinned,asset(pinned,code=7)))
            .put(release(latest,asset(latest,code=5)))
            .put(release(invalid,asset(invalid,code=99,sha="bad")))
            .put(release(draft,asset(draft,code=99)).put("draft",true))
        assertEquals(pinned,EnginePackage.find(releases,"arm64-v8a","sing-box",pinned).version)
        releases.put(release(catalogue,asset(catalogue,code=8)))
        assertEquals(catalogue,EnginePackage.find(releases,"arm64-v8a","sing-box",pinned).version)
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
        assertTrue(startup.contains("val beta=activity.prefs.getBoolean(MobileBetaUpdates.KEY,false)"))
        assertTrue(startup.contains("val releases=EnginePackages.catalogue(beta)"))
        assertTrue(startup.contains("EngineStartupPolicy.discover(releases,abi,installed,MobileBetaUpdates.TAG.takeIf {beta})"))
        val packages=source("EnginePackages")
        assertTrue(packages.contains("/releases?per_page=30"));assertTrue(packages.contains("/releases/tags/"))
        assertTrue(packages.contains("EnginePackage.find(releases, abi, kind, MobileBetaUpdates.TAG.takeIf { beta })"))
        val discovery=startup.substringAfter("fun onOpen(").substringBefore("internal fun installed(")
        assertFalse(discovery.contains("setPositiveButton("))
        assertTrue(startup.contains("activity.updateInstalledEngines(assets)"))
    }
}
