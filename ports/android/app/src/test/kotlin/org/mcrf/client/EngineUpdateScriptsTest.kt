package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class EngineUpdateScriptsTest {
    private fun rejects(block:()->Unit){try{block();fail("must reject")}catch(_:IllegalArgumentException){}}
    @Test fun requestDoesNotAcceptPathsKeysNightlyOrAutomaticWireBreak() {
        val dir=Files.createTempDirectory("mcrf-script-contract").toFile().canonicalFile
        try {
            val key="1".repeat(64)
            val request=EngineUpdateScripts.request("apply",dir,"demo.flux",key,false,consent=true)
            assertEquals("stable",request.getString("channel"));assertEquals(key,request.getString("pubkey"))
            assertFalse(request.getBoolean("allowWireBreak"));assertTrue(request.getBoolean("consent"))
            assertEquals("recover",EngineUpdateScripts.request("recover",dir,"demo.flux",key,false,consent=true,expectedId="demo",expectedWire=1).getString("action"))
            rejects {EngineUpdateScripts.request("recover",dir,"demo.flux",key,false,consent=true)}
            listOf("../demo.flux","demo.js","/demo.flux","a/b.flux").forEach {name->rejects {EngineUpdateScripts.request("apply",dir,name,key,false)}}
            rejects {EngineUpdateScripts.request("apply",dir,"demo.flux","short",false)}
            rejects {EngineUpdateScripts.request("apply",dir,"demo.flux",key,false,automatic=true,allowWireBreak=true)}
            rejects {EngineUpdateScripts.request("install-native-apk",dir,"demo.flux",key,false)}
        }finally{dir.delete()}
    }
    @Test fun capabilitiesContainNoTrustOrFilesystemMutationInputs() {
        val request=EngineUpdateScripts.request("capabilities",java.io.File("ignored"),"","",true)
        assertEquals(setOf("action"),request.keys().asSequence().toSet())
        assertFalse(EngineUpdateScripts.mutationPending())
    }
}
