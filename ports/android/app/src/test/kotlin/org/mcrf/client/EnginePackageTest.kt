package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.net.URI

class EnginePackageTest {
    @Test fun enduringWindowsCatalogueWinsRegardlessOfReleaseCreationOrder() {
        val old=releases().getJSONObject(0)
        val unified=JSONObject(old.toString()).put("tag_name",EnginePackage.CATALOGUE_RELEASE)
        unified.getJSONArray("assets").getJSONObject(0).put("browser_download_url",
            "https://github.com/${EnginePackage.REPOSITORY}/releases/download/${EnginePackage.CATALOGUE_RELEASE}/mcrf-engines-arm64-v8a-release.apk")
        assertEquals(EnginePackage.CATALOGUE_RELEASE,EnginePackage.find(JSONArray().put(old).put(unified),"arm64-v8a").version)
        unified.put("draft",true)
        assertEquals("android-engines-1",EnginePackage.find(JSONArray().put(old).put(unified),"arm64-v8a").version)
    }
    private val bytes = ByteArray(70000) { (it % 251).toByte() }
    private val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun releases(abi: String = "arm64-v8a") = JSONArray().put(JSONObject().put("tag_name", "android-engines-1").put("draft", false)
        .put("assets", JSONArray().put(JSONObject().put("name", "mcrf-engines-$abi-release.apk").put("state", "uploaded")
            .put("size", bytes.size).put("digest", "sha256:$hash")
            .put("browser_download_url", "https://github.com/${EnginePackage.REPOSITORY}/releases/download/android-engines-1/mcrf-engines-$abi-release.apk"))))
    private fun fails(block: () -> Unit) { try { block(); fail("must reject") } catch (_: IllegalArgumentException) { } }
    @Test fun exactAbiAndActualSize() {
        val asset = EnginePackage.find(releases(), "arm64-v8a")
        assertEquals(70000L, asset.bytes); assertEquals(hash, asset.sha256)
        assertEquals("armeabi-v7a", EnginePackage.abi(listOf("arm64-v8a", "armeabi-v7a"), false))
        assertEquals("arm64-v8a", EnginePackage.abi(listOf("arm64-v8a", "armeabi-v7a"), true))
        fails { EnginePackage.find(releases(), "x86_64") }
    }
    @Test fun rejectsUnpublishedDraftAndNoDigest() {
        fails { EnginePackage.find(JSONArray(), "arm64-v8a") }
        fails { EnginePackage.find(releases().apply { getJSONObject(0).put("draft", true) }, "arm64-v8a") }
        fails { EnginePackage.find(releases().apply { getJSONObject(0).put("prerelease", true) }, "arm64-v8a") }
        fails { EnginePackage.find(releases().apply { getJSONObject(0).getJSONArray("assets").getJSONObject(0).remove("digest") }, "arm64-v8a") }
    }
    @Test fun rejectsOtherRepoDowngradeOversizedAndBadDigest() {
        listOf("http://github.com/a.apk", "https://example.com/a.apk", "https://github.com/attacker/project/releases/download/android-engines-1/a.apk").forEach { url ->
            fails { EnginePackage.find(releases().apply { getJSONObject(0).getJSONArray("assets").getJSONObject(0).put("browser_download_url", url) }, "arm64-v8a") }
        }
        fails { EnginePackage.find(releases().apply { getJSONObject(0).getJSONArray("assets").getJSONObject(0).put("size", EnginePackage.MAX_BYTES + 1) }, "arm64-v8a") }
        assertFalse(EnginePackage.allowed(URI("https://evil.release-assets.githubusercontent.com/file")))
        assertFalse(EnginePackage.allowed(URI("http://release-assets.githubusercontent.com/file")))
        assertTrue(EnginePackage.allowed(URI("https://release-assets.githubusercontent.com/file?signature=fixture")))
    }
    @Test fun streamingIntegrityAndBounds() {
        val asset = EnginePackage.find(releases(), "arm64-v8a"); val output = ByteArrayOutputStream(); var last = 0L
        EnginePackage.copyVerified(ByteArrayInputStream(bytes), output, asset, { false }, { last = it })
        assertEquals(asset.bytes, last); assertArrayEquals(bytes, output.toByteArray())
        listOf(bytes.copyOf(69999), bytes + byteArrayOf(0), bytes.clone().apply { this[7] = 0 }).forEach {
            fails { EnginePackage.copyVerified(ByteArrayInputStream(it), ByteArrayOutputStream(), asset, { false }, {}) }
        }
    }
    @Test fun cancellationDoesNotAcceptPartialDownload() {
        try { EnginePackage.copyVerified(ByteArrayInputStream(bytes), ByteArrayOutputStream(), EnginePackage.find(releases(), "arm64-v8a"), { true }, {}); fail("must cancel") }
        catch (_: IllegalStateException) { }
    }
    @Test fun installedReceiptRequiresExactDownloadedApk() {
        val asset=EnginePackage.find(releases(),"arm64-v8a")
        assertTrue(asset.matchesInstalled(bytes.size.toLong()){ByteArrayInputStream(bytes)})
        assertFalse(asset.matchesInstalled(bytes.size.toLong()){ByteArrayInputStream(bytes.clone().apply {this[9]=0})})
        assertFalse(asset.matchesInstalled(bytes.size.toLong()){ByteArrayInputStream(bytes.copyOf(bytes.size-1))})
        assertFalse(asset.matchesInstalled(bytes.size.toLong()){ByteArrayInputStream(bytes+byteArrayOf(1))})
    }
    @Test fun installedReceiptRefusesOldSizeWithoutReadingAndHandlesMissingFile() {
        val asset=EnginePackage.find(releases(),"arm64-v8a")
        assertFalse(asset.matchesInstalled(bytes.size.toLong()-1){error("must not open a different-sized installed APK")})
        assertFalse(asset.matchesInstalled(bytes.size.toLong()){throw java.io.FileNotFoundException("fixture")})
    }
    @Test fun planPreservesMixedImportWithoutCommitting() {
        val ordinary = "socks://127.0.0.1:1080#fixture"
        val carrier = JSONObject().put("type", "openflux").put("name", "fixture").put("secret", "fixture encryption secret")
            .put("negotiate", true).put("transports", JSONArray().put(JSONObject().put("type", "direct").put("dial", "127.0.0.1:24444")))
        val link = OpenFluxProfile.share(carrier); var calls = 0
        val plan = ImportPlan.prepare("$ordinary\n$link\nhttps://fixture.invalid/sub\nhttps://fixture.invalid/sub") {
            calls++; SubscriptionClient.Downloaded(ServerImport.read(ordinary), JSONObject().put("total", 500))
        }
        assertEquals(1, calls); assertEquals(setOf("openflux"), plan.engines); assertEquals(2, plan.entries.size)
        assertEquals(500, plan.subscriptions.values.single().metadata.getInt("total"))
        fails { ImportPlan.prepare("qwdtt://placeholder") { error("no fetch") } }
    }
}
