package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AppReleaseTest {
    @Test fun offlineEditionKeepsItsEditionAndSelectsMatchingAbiBeforeUniversal(){
        val ordinary=release("mcrf-android-universal-release.apk")
        assertNull(AppRelease.find(JSONArray().put(ordinary),"arm64-v8a",true))
        val universal=release("mcrf-android-offline-universal-release.apk")
        for(abi in listOf("arm64-v8a","armeabi-v7a","x86_64")){
            val row=JSONObject(universal.toString())
            row.getJSONArray("assets").put(release("mcrf-android-offline-$abi-release.apk").getJSONArray("assets").getJSONObject(0))
            row.getJSONArray("assets").put(ordinary.getJSONArray("assets").getJSONObject(0))
            assertTrue(AppRelease.find(JSONArray().put(row),abi,true)!!.asset.url.path.endsWith("/mcrf-android-offline-$abi-release.apk"))
            assertTrue(AppRelease.find(JSONArray().put(universal),abi,true)!!.asset.url.path.endsWith("/mcrf-android-offline-universal-release.apk"))
            assertTrue(AppRelease.find(JSONArray().put(row),abi,false)!!.asset.url.path.endsWith("/mcrf-android-universal-release.apk"))
        }
        assertNull(AppRelease.find(JSONArray().put(release("mcrf-android-offline-x86_64-release.apk")),"arm64-v8a",true))
        val damaged=release("mcrf-android-offline-arm64-v8a-release.apk")
        damaged.getJSONArray("assets").getJSONObject(0).put("digest","")
        assertThrows(IllegalArgumentException::class.java){AppRelease.find(JSONArray().put(damaged),"arm64-v8a",true)}
    }
    @Test fun bridgeUniversalPreferredAcrossAllSupportedAbis(){
        val row=release("mcrf-android-universal-release.apk")
        val legacy=release().getJSONArray("assets").getJSONObject(0)
        row.getJSONArray("assets").put(0,legacy).put(release("mcrf-android-universal-release.apk").getJSONArray("assets").getJSONObject(0))
        for(abi in listOf("arm64-v8a","armeabi-v7a","x86_64")){
            val found=AppRelease.find(JSONArray().put(row),abi)!!
            assertTrue(found.asset.url.path.endsWith("/mcrf-android-universal-release.apk"))
            assertEquals(abi,found.asset.abi)
        }
    }
    @Test fun bridgeLegacyFallbackAndWindowsUniversalCannotBecomeAndroidUpdate(){
        val windows=release("mcrf-android-universal-release.apk").put("tag_name","v1.1")
        val found=AppRelease.find(JSONArray().put(windows).put(release()),"arm64-v8a")!!
        assertEquals("android-v0.1.0",found.asset.version)
        assertTrue(found.asset.url.path.endsWith("/arm64-v8a-release.apk"))
    }
    @Test fun bridgeUniversalStillRequiresTrustedHostAndDigest(){
        for(change in listOf("browser_download_url" to "https://other.invalid/app.apk","digest" to "")){
            val row=release("mcrf-android-universal-release.apk")
            row.getJSONArray("assets").getJSONObject(0).put(change.first,change.second)
            try{AppRelease.find(JSONArray().put(row),"arm64-v8a");fail("must reject")}catch(_:IllegalArgumentException){}
        }
    }
    @Test fun releaseNotesAreTakenFromGithubAndBounded(){val row=release().put("body","Оптимизирован дизайн.\n"+"x".repeat(7000));val found=AppRelease.find(JSONArray().put(row),"arm64-v8a")!!;assertTrue(found.notes.startsWith("Оптимизирован дизайн."));assertEquals(6000,found.notes.length)}
    private fun release(name:String="arm64-v8a-release.apk",draft:Boolean=false)=JSONObject().put("tag_name","android-v0.1.0").put("draft",draft).put("prerelease",false)
        .put("assets",JSONArray().put(JSONObject().put("name",name).put("state","uploaded").put("size",12345).put("digest","sha256:"+"a".repeat(64))
            .put("browser_download_url","https://github.com/${EnginePackage.REPOSITORY}/releases/download/android-v0.1.0/$name")))
    @Test fun windowsOnlyAndOtherAbiAreNotAndroidUpdates(){assertNull(AppRelease.find(JSONArray().put(release("mcrf.exe")),"arm64-v8a"));assertNull(AppRelease.find(JSONArray().put(release("armeabi-v7a-release.apk")),"arm64-v8a"))}
    @Test fun draftIsSkippedAndExactDigestSizeAndPageAreKept(){val found=AppRelease.find(JSONArray().put(release(draft=true)).put(release()),"arm64-v8a")!!;assertEquals(12345L,found.asset.bytes);assertFalse(found.prerelease);assertEquals("a".repeat(64),found.asset.sha256);assertEquals("https://github.com/${EnginePackage.REPOSITORY}/releases/tag/android-v0.1.0",found.page.toString())}
    @Test fun badHostAndMissingDigestCannotBecomeAnOffer(){for(change in listOf("browser_download_url" to "https://other.invalid/app.apk","digest" to "")){val row=release();row.getJSONArray("assets").getJSONObject(0).put(change.first,change.second);try{AppRelease.find(JSONArray().put(row),"arm64-v8a");fail("must reject")}catch(_:IllegalArgumentException){}}}
    @Test fun designatedBetaRequiresOptInAndIsInvisibleToLegacyAssetSelection(){
        val beta=release(MobileBetaUpdates.APP_ASSET).put("tag_name",MobileBetaUpdates.TAG).put("prerelease",true)
        beta.getJSONArray("assets").getJSONObject(0).put("browser_download_url",
            "https://github.com/${EnginePackage.REPOSITORY}/releases/download/${MobileBetaUpdates.TAG}/${MobileBetaUpdates.APP_ASSET}")
        val rows=JSONArray().put(beta).put(release())
        assertFalse(AppRelease.find(rows,"arm64-v8a")!!.prerelease)
        assertTrue(AppRelease.find(rows,"arm64-v8a",beta=true)!!.prerelease)
        assertNull(AppRelease.find(JSONArray().put(beta),"arm64-v8a",offline=true,beta=true))
        beta.put("draft",true)
        assertFalse(AppRelease.find(rows,"arm64-v8a",beta=true)!!.prerelease)
    }

    @Test fun stablePromotionMustUpgradeInstalledBetaCode(){
        val name="mcrf-android-universal-release.apk"
        val stable=release(name).put("tag_name","android-v2.0.0")
        val asset=stable.getJSONArray("assets").getJSONObject(0)
        asset.put("browser_download_url","https://github.com/${EnginePackage.REPOSITORY}/releases/download/android-v2.0.0/$name")
        val rows=JSONArray().put(stable)
        asset.put("label","mcrf-version-code=10065")
        assertNull(AppRelease.find(rows,"arm64-v8a",installedCode=10178))
        for(code in 10178L..10179L){
            asset.put("label","mcrf-version-code=$code")
            val promoted=AppRelease.find(rows,"arm64-v8a",installedCode=10178)
            assertEquals(code,promoted?.asset?.versionCode)
            assertTrue(AppUpdatePresentation.newer(promoted!!.asset.version,"2.0.0-beta.10178"))
        }
        stable.put("tag_name","android-v2.0.0-beta.10179")
        assertNull(AppRelease.find(rows,"arm64-v8a",beta=true,installedCode=10178))
    }
}
