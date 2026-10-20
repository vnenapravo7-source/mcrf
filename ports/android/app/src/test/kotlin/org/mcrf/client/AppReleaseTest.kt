package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AppReleaseTest {
    @Test fun releaseNotesAreTakenFromGithubAndBounded(){val row=release().put("body","Оптимизирован дизайн.\n"+"x".repeat(7000));val found=AppRelease.find(JSONArray().put(row),"arm64-v8a")!!;assertTrue(found.notes.startsWith("Оптимизирован дизайн."));assertEquals(6000,found.notes.length)}
    private fun release(name:String="arm64-v8a-release.apk",draft:Boolean=false)=JSONObject().put("tag_name","android-v0.1.0").put("draft",draft).put("prerelease",true)
        .put("assets",JSONArray().put(JSONObject().put("name",name).put("state","uploaded").put("size",12345).put("digest","sha256:"+"a".repeat(64))
            .put("browser_download_url","https://github.com/${EnginePackage.REPOSITORY}/releases/download/android-v0.1.0/$name")))
    @Test fun windowsOnlyAndOtherAbiAreNotAndroidUpdates(){assertNull(AppRelease.find(JSONArray().put(release("mcrf.exe")),"arm64-v8a"));assertNull(AppRelease.find(JSONArray().put(release("armeabi-v7a-release.apk")),"arm64-v8a"))}
    @Test fun draftIsSkippedAndExactDigestSizeAndPageAreKept(){val found=AppRelease.find(JSONArray().put(release(draft=true)).put(release()),"arm64-v8a")!!;assertEquals(12345L,found.asset.bytes);assertTrue(found.prerelease);assertEquals("a".repeat(64),found.asset.sha256);assertEquals("https://github.com/${EnginePackage.REPOSITORY}/releases/tag/android-v0.1.0",found.page.toString())}
    @Test fun badHostAndMissingDigestCannotBecomeAnOffer(){for(change in listOf("browser_download_url" to "https://other.invalid/app.apk","digest" to "")){val row=release();row.getJSONArray("assets").getJSONObject(0).put(change.first,change.second);try{AppRelease.find(JSONArray().put(row),"arm64-v8a");fail("must reject")}catch(_:IllegalArgumentException){}}}
}
