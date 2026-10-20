package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject

class WgAddonPolicyTest {
    @Test fun installedSingBoxBlocksAddonDownloadEvenWhenSteerIsSelected() {
        for(primary in listOf(null,"singbox","steer")) {
            assertThrows(IllegalArgumentException::class.java) {WgAddonPolicy.requireDownloadAllowed(primary,true)}
        }
        WgAddonPolicy.requireDownloadAllowed("steer",false)
    }
    @Test fun installedSingBoxAlwaysWinsEvenWithSteerSelectedAndAddonInstalled() {
        for(primary in listOf(null,"singbox","steer")) for(compact in listOf(false,true)) {
            assertEquals(WgAddonPolicy.Backend.SING_BOX,WgAddonPolicy.backend(primary,true,compact))
            assertFalse(WgAddonPolicy.offerDownload(primary,true,compact,listOf("wireguard","awg")))
        }
    }
    @Test fun steerWithoutSingBoxOffersOnlyMissingCompactModule() {
        assertEquals(WgAddonPolicy.Backend.NEED_COMPACT_WG,WgAddonPolicy.backend("steer",false,false))
        assertEquals(WgAddonPolicy.Backend.COMPACT_WG,WgAddonPolicy.backend("steer",false,true))
        for(protocol in listOf("wg","wireguard","awg")) assertTrue(WgAddonPolicy.offerDownload("steer",false,false,listOf(protocol)))
        assertFalse(WgAddonPolicy.offerDownload("steer",false,true,listOf("awg")))
        assertFalse(WgAddonPolicy.offerDownload("steer",false,false,listOf("tuic","vless")))
    }
    @Test fun singBoxChoiceNeverPermitsAddonDownloadOrAutomaticFallback() {
        assertThrows(IllegalArgumentException::class.java) {WgAddonPolicy.requireDownloadAllowed("singbox")}
        assertEquals(WgAddonPolicy.Backend.NEED_SING_BOX,WgAddonPolicy.backend("singbox",false,true))
        assertFalse(WgAddonPolicy.offerDownload("singbox",false,false,listOf("awg")))
    }
    @Test fun missingChoiceDoesNotInstallAnythingAndUnknownChoiceFails() {
        assertEquals(WgAddonPolicy.Backend.NEED_PRIMARY,WgAddonPolicy.backend(null,false,true))
        assertThrows(IllegalArgumentException::class.java) {WgAddonPolicy.requireDownloadAllowed(null)}
        assertThrows(IllegalArgumentException::class.java) {WgAddonPolicy.backend("other",true,true)}
        WgAddonPolicy.requireDownloadAllowed("steer")
    }

    private fun graph(selected:String)=JSONObject().put("route",JSONObject().put("final","proxy"))
        .put("outbounds",JSONArray().put(JSONObject().put("tag","proxy").put("type","selector").put("default",selected).put("outbounds",JSONArray(listOf("plain","wg"))))
            .put(JSONObject().put("tag","plain").put("type","vless")))
        .put("endpoints",JSONArray().put(JSONObject().put("tag","wg").put("type","wireguard")))
    @Test fun unselectedStoredWireGuardDoesNotOverrideSteerForOtherProtocols() {
        assertFalse(WgAddonPolicy.usesWireGuard(graph("plain")))
        assertTrue(WgAddonPolicy.usesWireGuard(graph("wg")))
        val automatic=graph("plain");automatic.getJSONArray("outbounds").getJSONObject(0).put("type","urltest")
        assertTrue(WgAddonPolicy.usesWireGuard(automatic))
    }
    @Test fun followsDetoursAndBoundsCyclesWithoutRecursionOrDuplicateQueueGrowth() {
        val source=graph("plain")
        source.getJSONArray("outbounds").getJSONObject(1).put("detour","wg")
        assertTrue(WgAddonPolicy.usesWireGuard(source))
        source.getJSONArray("outbounds").getJSONObject(1).put("detour","proxy")
        assertFalse(WgAddonPolicy.usesWireGuard(source))
        source.getJSONArray("outbounds").put(JSONObject().put("tag","plain").put("type","wireguard"))
        assertThrows(IllegalArgumentException::class.java) {WgAddonPolicy.usesWireGuard(source)}
    }
    @Test fun actualImportedCatalogueGraphSelectsWireGuardOnlyWhenActive() {
        val key=java.util.Base64.getEncoder().encodeToString(ByteArray(32) {(it+1).toByte()})
        val wg=ServerImport.read("[Interface]\nPrivateKey=$key\nAddress=10.77.0.2/32\n[Peer]\nPublicKey=$key\nEndpoint=192.0.2.1:51820\nAllowedIPs=0.0.0.0/0").single()
        val plain=ServerImport.read("vless://00000000-0000-4000-8000-000000000001@example.org:443?security=none&type=tcp&encryption=none#plain").single()
        val all=listOf(plain,wg)
        assertTrue(WgAddonPolicy.usesWireGuard(ServerImport.build(all,wg.id,false,60,50,"Cloudflare")))
        assertFalse(WgAddonPolicy.usesWireGuard(ServerImport.build(all,plain.id,false,60,50,"Cloudflare")))
        assertTrue(WgAddonPolicy.usesWireGuard(ServerImport.build(all,wg.id,true,60,50,"Cloudflare")))
    }
}
