package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class Csqtt25AdditionalAbiTest {
    @Test fun everyAbiRequiresItsOwnCompleteCsqtt25PairWithoutLegacyFallback() {
        for(abi in listOf("arm64-v8a","armeabi-v7a","x86_64")) {
            val pair=listOf("lib/$abi/libcsqtt25.so","lib/$abi/libcsqtt25_transport.so")
            assertTrue(EngineModules.validNativeEntries("csqtt25",abi,pair))
            assertFalse(EngineModules.validNativeEntries("csqtt25",abi,pair.take(1)))
            assertFalse(EngineModules.validNativeEntries("csqtt25",abi,listOf("lib/$abi/libqwdtt.so","lib/$abi/libcsqtt_transport.so")))
            val other=if(abi=="arm64-v8a")"x86_64"else"arm64-v8a"
            assertFalse(EngineModules.validNativeEntries("csqtt25",abi,pair.map {it.replace(abi,other)}))
        }
    }
    @Test fun downloadSelectsMatchingNewAbiAssetAndUnpublishedAbiStaysExplicit() {
        val tag=EnginePackage.CATALOGUE_RELEASE
        val rows=JSONArray().put(JSONObject().put("tag_name",tag).put("assets",JSONArray().apply {
            for(abi in listOf("armeabi-v7a","x86_64")) {
                val name=EngineModules.assetName("csqtt25",abi)
                put(JSONObject().put("name",name).put("state","uploaded").put("size",100)
                    .put("digest","sha256:"+"a".repeat(64)).put("browser_download_url","https://github.com/${EnginePackage.REPOSITORY}/releases/download/$tag/$name"))
            }
        }))
        for(abi in listOf("armeabi-v7a","x86_64")) {
            val selected=EnginePackage.find(rows,abi,"csqtt25")
            assertEquals(abi,selected.abi)
            assertTrue(selected.url.path.endsWith(EngineModules.assetName("csqtt25",abi)))
        }
        assertThrows(EnginePackage.Unpublished::class.java){EnginePackage.find(rows,"arm64-v8a","csqtt25")}
    }
}
