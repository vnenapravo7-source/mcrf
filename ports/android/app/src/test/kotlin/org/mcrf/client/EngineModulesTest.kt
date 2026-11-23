package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test
import org.json.*

class EngineModulesTest {
    @Test fun preflightUsesTheProfileGenerationNotItsSharedProtocolLabel(){
        val legacy=JSONObject().put("type","csqtt")
        val modern=JSONObject(legacy.toString()).put("csqtt_generation","2.5")
        assertEquals("csqtt",EngineModules.carrierKind(legacy))
        assertEquals("csqtt25",EngineModules.carrierKind(modern))
        assertNotEquals(EngineModules.packageName(EngineModules.carrierKind(legacy)),EngineModules.packageName(EngineModules.carrierKind(modern)))
        assertTrue(EngineModules.validNativeEntries("csqtt25","arm64-v8a",listOf("lib/arm64-v8a/libcsqtt25.so","lib/arm64-v8a/libcsqtt25_transport.so")))
        assertTrue(EngineModules.validNativeEntries("csqtt25","armeabi-v7a",listOf("lib/armeabi-v7a/libcsqtt25.so","lib/armeabi-v7a/libcsqtt25_transport.so")))
        assertThrows(IllegalArgumentException::class.java){EngineModules.carrierKind(JSONObject(legacy.toString()).put("csqtt_generation","future"))}
    }
    @Test fun eachEngineHasItsOwnIdentity(){
        assertEquals(setOf("csqtt","csqtt20","csqtt215","csqtt25","qwdtt","wdtt","openflux","olcrtc","dnstt",
            VayDnsProfile.KIND,SlipstreamProfile.KIND,SstpProfile.KIND,MasterDnsProfile.KIND,"warpscout","tgws",
            S3BypassProfile.KIND,"xray","tls-core","protocols"),EngineModules.kinds)
        assertEquals(EngineModules.kinds.size,EngineModules.kinds.map{EngineModules.packageName(it)}.toSet().size)
        EngineModules.kinds.forEach {assertEquals(it,EngineModules.kind(EngineModules.packageName(it)))}
    }
    @Test fun s3RequiresItsOwnExecutablePackageOnEverySupportedAbi(){
        assertEquals("org.mcrf.engines.s3bypass",EngineModules.packageName(S3BypassProfile.KIND))
        for(abi in listOf("arm64-v8a","armeabi-v7a","x86_64")){
            assertTrue(EngineModules.validNativeEntries(S3BypassProfile.KIND,abi,listOf("lib/$abi/libs3bypass.so")))
            assertFalse(EngineModules.validNativeEntries(S3BypassProfile.KIND,abi,listOf("lib/$abi/libxray.so")))
            assertFalse(EngineModules.validNativeEntries(S3BypassProfile.KIND,abi,listOf("lib/$abi/libs3bypass.so","lib/$abi/libbox.so")))
        }
    }
    @Test fun csqttHasItsBridgeButDoesNotInstallQwdttPackage(){assertEquals(setOf("libqwdtt.so","libcsqtt_transport.so"),EngineModules.libraries("csqtt"));assertNotEquals(EngineModules.packageName("csqtt"),EngineModules.packageName("qwdtt"))}
    @Test fun packagesHaveOnlyTheirDeclaredExecutableAndTransportLibraries(){
        val paired=mapOf("csqtt" to setOf("libqwdtt.so","libcsqtt_transport.so"),
            "csqtt20" to setOf("libcsqtt20.so","libcsqtt_transport.so"),
            "csqtt215" to setOf("libcsqtt215.so","libcsqtt_transport.so"),
            "csqtt25" to setOf("libcsqtt25.so","libcsqtt25_transport.so"),
            SlipstreamProfile.KIND to setOf("libslipstream.so","libslipstream_core.so"))
        EngineModules.kinds.forEach {kind->
            val expected=paired[kind]?:setOf(if(kind=="tls-core")"libtls_core.so"else"lib$kind.so")
            assertEquals(expected,EngineModules.libraries(kind))
            for(abi in listOf("arm64-v8a","armeabi-v7a","x86_64")){
                val entries=expected.map {"lib/$abi/$it"}
                assertTrue(kind,EngineModules.validNativeEntries(kind,abi,entries))
                assertFalse(kind,EngineModules.validNativeEntries(kind,abi,entries+"lib/$abi/libforeign.so"))
                if(entries.size>1)assertFalse(kind,EngineModules.validNativeEntries(kind,abi,entries.dropLast(1)))
            }
        }
    }
    @Test fun optionalCoresHaveExplicitMappingsAndNoLegacyAlias(){assertEquals("org.mcrf.engines.tlscore",EngineModules.packageName("tls-core"));assertNull(EngineModules.kind("org.mcrf.engines.tls-core"));assertTrue(EngineModules.validNativeEntries("tls-core","arm64-v8a",listOf("lib/arm64-v8a/libtls_core.so")));assertFalse(EngineModules.validNativeEntries("xray","arm64-v8a",listOf("lib/arm64-v8a/libxray.so","lib/arm64-v8a/libbox.so")))}
    @Test fun wrongPackageAndUnknownKindAreRefused(){assertNull(EngineModules.kind("org.mcrf.client"));try{EngineModules.packageName("../bad");fail()}catch(_:IllegalArgumentException){}}
    @Test fun releaseLookupSelectsOnlyRequestedEngineAndAbi(){
        val filename=EngineModules.assetName("openflux","arm64-v8a");val asset=JSONObject().put("name",filename).put("state","uploaded").put("size",17).put("digest","sha256:"+"a".repeat(64)).put("browser_download_url","https://github.com/${EnginePackage.REPOSITORY}/releases/download/android-v1/$filename")
        val release=JSONArray().put(JSONObject().put("tag_name","android-v1").put("assets",JSONArray().put(asset)))
        assertEquals("openflux",EnginePackage.find(release,"arm64-v8a","openflux").engine)
        try{EnginePackage.find(release,"arm64-v8a","qwdtt");fail()}catch(_:IllegalArgumentException){}
    }
    @Test fun tlsCoreUsesPublicAssetKindAndPrivateAndroidFlavorMapping(){
        val filename=EngineModules.assetName("tls-core","arm64-v8a")
        assertEquals("mcrf-engine-tls-core-arm64-v8a-release.apk",filename)
        val asset=JSONObject().put("name",filename).put("state","uploaded").put("size",17).put("digest","sha256:"+"b".repeat(64)).put("browser_download_url","https://github.com/${EnginePackage.REPOSITORY}/releases/download/android-v2/$filename")
        val releases=JSONArray().put(JSONObject().put("tag_name","android-v2").put("assets",JSONArray().put(asset)))
        assertEquals("tls-core",EnginePackage.find(releases,"arm64-v8a","tls-core").engine)
        assertEquals("throneproj/sing-box",EngineModules.sourceRepository("tls-core"))
        assertEquals("authenticated-loopback-socks5-tcp-udp",EngineModules.ingress("xray"))
        assertEquals("authenticated-loopback-socks5-tcp",EngineModules.ingress("tls-core"))
        try{EnginePackage.find(releases,"arm64-v8a","xray");fail()}catch(_:EnginePackage.Unpublished){}
    }
}
