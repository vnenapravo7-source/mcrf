package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BsEngineContractTest {
    private fun archive(schema:Int,extraWdtt:Boolean=false):java.io.File {
        val file=java.io.File.createTempFile("bs-layout-", ".zip")
        val engines=if(schema>=3)BsEngineContract.engines else BsEngineContract.legacyEngines
        val files=when(schema){4->BsEngineContract.currentFiles;3->BsEngineContract.splitFiles;2->BsEngineContract.compactFiles;else->BsEngineContract.files}+if(extraWdtt)setOf("libwdtt.so")else emptySet()
        val native=ByteArray(64).also{it[0]=127;it[1]=69;it[2]=76;it[3]=70;it[4]=2;it[5]=1;it[6]=1;it[18]=183.toByte()}
        val nodes=JSONObject();val libraries=JSONObject()
        engines.forEach{kind->nodes.put(kind,JSONObject().put("executable",if(schema>=2&&kind!="wdtt")"libbs.so"else when(kind){"csqtt","csqtt20","csqtt215"->"libbs_csqtt.so";else->"lib$kind.so"}))}
        files.forEach{libraries.put(it,JSONObject().put("bytes",native.size).put("sha256",BsEngineContract.sha(native.inputStream())))}
        val json=JSONObject().put("schema",schema).put("package",BsEngineContract.PACKAGE).put("engines",nodes)
            .put("native",JSONObject().put("arm64-v8a",libraries))
        if(schema==4)json.put("layout",BsEngineContract.LAYOUT)
        java.util.zip.ZipOutputStream(file.outputStream()).use{zip->
            files.forEach{zip.putNextEntry(java.util.zip.ZipEntry("lib/arm64-v8a/$it"));zip.write(native);zip.closeEntry()}
            zip.putNextEntry(java.util.zip.ZipEntry(BsEngineContract.ASSET));zip.write(json.toString().toByteArray());zip.closeEntry()
        }
        return file
    }
    @Test fun newLayoutRejectsWdttButOldContainersRemainReadableForOtherEngines(){
        for(schema in 1..4){val file=archive(schema);try{java.util.zip.ZipFile(file).use{zip->
            val catalog=BsEngineContract.read(zip,"arm64-v8a")
            assertEquals(schema,catalog.schema);assertEquals(BsEngineContract.engines,catalog.executables.keys)
            assertFalse(catalog.executables.containsKey("wdtt"))
        }}finally{file.delete()}}
        val file=archive(3,true);try{java.util.zip.ZipFile(file).use{zip->
            try{BsEngineContract.read(zip,"arm64-v8a");fail()}catch(_:IllegalArgumentException){}
        }}finally{file.delete()}
    }
    @Test fun trustedModesStayLocalToEachProcessAndIgnoreInheritedValues(){
        val inherited=mapOf("MCRF_BS_MODE" to "from-import", "MCRF_BS_TRANSPORT" to "/tmp/untrusted", "MCRF_BS_RUST_MODE" to "2.0", "XDG_CACHE_HOME" to "private")
        val a=inherited.toMutableMap();val b=inherited.toMutableMap()
        BsEngineContract.configureHostMode(a,"libbs.so","qwdtt")
        BsEngineContract.configureHostMode(b,"libbs.so","csqtt215")
        assertEquals("qwdtt",a["MCRF_BS_MODE"]);assertEquals("csqtt215",b["MCRF_BS_MODE"])
        assertFalse(a.containsKey("MCRF_BS_TRANSPORT"));assertFalse(b.containsKey("MCRF_BS_TRANSPORT"))
        assertFalse(a.containsKey("MCRF_BS_RUST_MODE"));assertFalse(b.containsKey("MCRF_BS_RUST_MODE"))
        assertEquals("private",a["XDG_CACHE_HOME"])
        BsEngineContract.configureHostMode(b,"libwdtt.so","wdtt")
        assertFalse(b.containsKey("MCRF_BS_MODE"));assertEquals("qwdtt",a["MCRF_BS_MODE"])
        BsEngineContract.configureHostMode(b,"libbs_csqtt.so","csqtt20")
        assertEquals("libcsqtt20_transport.so",b["MCRF_BS_TRANSPORT"])
        try{BsEngineContract.configureHostMode(b,"libbs.so","wdtt");fail()}catch(_:IllegalArgumentException){}
    }
    @Test fun multicallHasTwoLibrariesAndTrustedChildGenerations(){
        assertEquals(setOf("libbs.so","libcsqtt_transport.so"),BsEngineContract.currentFiles)
        val parent=mapOf("MCRF_BS_RUST_MODE" to "from-import","MCRF_BS_LAYOUT" to "unknown")
        for((kind,mode) in mapOf("csqtt" to "2.1.9","csqtt20" to "2.0","csqtt215" to "2.1.5","qwdtt" to null)){
            val env=parent.toMutableMap();BsEngineContract.configureHostMode(env,"libbs.so",kind,true)
            assertEquals(mode,env["MCRF_BS_RUST_MODE"]);assertEquals(BsEngineContract.LAYOUT,env["MCRF_BS_LAYOUT"])
        }
        for(version in listOf("2.5.1","2.5.2","2.5.3")){
            val env=parent.toMutableMap();BsEngineContract.configureHostMode(env,"libbs.so","csqtt25",true,"swg-panel-$version")
            assertEquals(version,env["MCRF_BS_RUST_MODE"])
        }
        assertEquals("from-import",parent["MCRF_BS_RUST_MODE"])
        try{BsEngineContract.rustMode("csqtt25","unknown");fail()}catch(_:IllegalArgumentException){}
    }
    private fun releases(engine:String,abi:String="arm64-v8a"):JSONArray {
        val name="mcrf-engine-$engine-$abi-release.apk"
        val asset=JSONObject().put("name",name).put("state","uploaded").put("size",123).put("digest","sha256:"+"a".repeat(64))
            .put("label","mcrf-version-code=1").put("browser_download_url","https://github.com/${EnginePackage.REPOSITORY}/releases/download/${EnginePackage.CATALOGUE_RELEASE}/$name")
        return JSONArray().put(JSONObject().put("tag_name",EnginePackage.CATALOGUE_RELEASE).put("assets",JSONArray().put(asset)))
    }
    @Test fun allLogicalIdsResolveToOnePhysicalAsset(){
        BsEngineContract.engines.forEach {assertEquals("bs",EnginePackage.find(releases("bs"),"arm64-v8a",it).engine)}
        assertEquals("org.mcrf.engines.bs",EngineModules.packageName("bs"))
        assertEquals("org.mcrf.engines.csqtt20",EngineModules.packageName("csqtt20"))
    }
    @Test fun unpublishedContainerKeepsOldDownloads(){assertEquals("csqtt25",EnginePackage.find(releases("csqtt25"),"arm64-v8a","csqtt25").engine)}
    @Test fun noArchitectureFallbackOrUniversalContainer(){
        try{EnginePackage.find(releases("bs","x86_64"),"arm64-v8a","bs");fail()}catch(_:EnginePackage.Unpublished){}
        try{EnginePackage.find(releases("bs","universal"),"universal","bs");fail()}catch(_:IllegalArgumentException){}
    }
    @Test fun logicalCoverageDoesNotIncludeOtherEngines(){
        BsEngineContract.engines.forEach{assertTrue(BsEngineContract.covers("bs",it))}
        assertFalse(BsEngineContract.covers("bs","openflux"));assertFalse(BsEngineContract.covers("bs","s3bypass"))
        assertFalse(BsEngineContract.covers("bs","wdtt"))
        assertEquals("org.mcrf.engines.wdtt",EngineModules.packageName("wdtt"))
        assertEquals(setOf("libwdtt.so"),EngineModules.libraries("wdtt"))
        assertFalse("libwdtt.so" in BsEngineContract.currentFiles)
        assertTrue("libwdtt.so" in BsEngineContract.compactFiles) // installed schema2 still readable
        assertEquals(listOf("qwdtt","csqtt20"),BsEngineMigration.removable(listOf("wdtt","qwdtt","csqtt20","openflux")))
        assertEquals("wdtt",EnginePackage.find(releases("wdtt"),"arm64-v8a","wdtt").engine)
        try{EnginePackage.find(releases("bs"),"arm64-v8a","wdtt");fail()}catch(_:EnginePackage.Unpublished){}
    }
    @Test fun generationProbeOrderRemainsUnchanged(){assertEquals(listOf("2.5/swg-panel-2.5.3","2.5/swg-panel-2.5.2","2.5/swg-panel-2.5.1","legacy/","2.1.5/","2.0/"),CsqttAutoDetection.order.map{it.token})}
    @Test fun refusalOrLostReplacementAlwaysStopsMigration(){
        assertTrue(BsEngineMigration.advance(true,true,true))
        assertFalse(BsEngineMigration.advance(false,true,true));assertFalse(BsEngineMigration.advance(true,false,true));assertFalse(BsEngineMigration.advance(true,true,false))
    }
    @Test fun lowerVersionOfNewPackageFamilyStillOffered(){
        val local=mapOf("csqtt25" to EngineStartupPolicy.Installed(321,"b".repeat(64),99))
        val found=EngineStartupPolicy.discover(releases("bs"),"arm64-v8a",local)
        assertEquals(listOf("bs"),found.updates.map{it.engine})
        assertEquals(1,EngineStartupPolicy.stillNeeded(found.updates,local).size)
    }
}
