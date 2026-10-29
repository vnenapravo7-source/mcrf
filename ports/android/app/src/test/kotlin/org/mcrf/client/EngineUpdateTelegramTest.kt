package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test
import java.net.URI
import java.io.File

class EngineUpdateTelegramTest {
    private fun source(name:String)=listOf(File("src/main/kotlin/org/mcrf/client/$name.kt"),File("app/src/main/kotlin/org/mcrf/client/$name.kt")).first {it.isFile}.readText()
    @Test fun tgIsDedicatedAndHasOnlyPortExecutable(){
        assertEquals("org.mcrf.engines.tgws",EngineModules.packageName("tgws"))
        assertEquals(setOf("libtgws.so"),EngineModules.libraries("tgws"))
        assertEquals("mcrf-engine-tgws-arm64-v8a-release.apk",EngineModules.assetName("tgws","arm64-v8a"))
        assertFalse(EngineModules.validNativeEntries("tgws","arm64-v8a",listOf("lib/arm64-v8a/libtgws.so","lib/arm64-v8a/libbox.so")))
    }
    @Test fun tgRejectsCarrierCliAndWrongSource(){
        EngineUpdateTelegram.validateMetadata("tgws",1,EngineUpdateTelegram.REPOSITORY,EngineUpdateTelegram.COMMIT,"port-v1")
        try{EngineUpdateTelegram.validateMetadata("tgws",1,EngineUpdateTelegram.REPOSITORY,EngineUpdateTelegram.COMMIT,"mcrf-config");fail()}catch(_:IllegalArgumentException){}
        try{EngineUpdateTelegram.validateMetadata("tgws",1,"other/repo",EngineUpdateTelegram.COMMIT,"port-v1");fail()}catch(_:IllegalArgumentException){}
    }
    @Test fun downloadWeightUsesOnlyKnownMatchingAbiMetadataNotZeroOrUniversalEstimate(){
        val a=EnginePackage("arm64-v8a","v1",URI("https://github.com/example"),12345,"a".repeat(64),"steer")
        assertNull(EngineStorage.downloadBytes("steer","arm64-v8a",null))
        assertEquals(12345L,EngineStorage.downloadBytes("steer","arm64-v8a",a))
        assertNull(EngineStorage.downloadBytes("steer","x86_64",a))
        assertNull(EngineStorage.downloadBytes("sing-box","arm64-v8a",a))
        assertNull(EngineStorage.downloadBytes("steer","arm64-v8a",a.copy(bytes=0)))
    }
    @Test fun compactSizeKeepsActualApkAndDeviceFactsSeparate(){
        val installed=EngineStorage.Size("org.mcrf.engines.steer",100,500,1024)
        assertEquals(EngineStorage.DisplayBytes(100,1024),EngineStorage.displayBytes(999,installed))
        assertEquals(EngineStorage.DisplayBytes(999,null),EngineStorage.displayBytes(999,null))
        assertEquals(EngineStorage.DisplayBytes(null,null),EngineStorage.displayBytes(null,null))
        assertEquals(EngineStorage.DisplayBytes(null,null),EngineStorage.displayBytes(0,installed.copy(apk=0,bytes=0)))
    }
    @Test fun engineInventoryOffersScopedBulkRemoval(){
        val protocols=source("ProtocolsSheet");val storage=source("EngineStorage")
        assertTrue(protocols.contains("stop-engine-processes"));assertTrue(source("MainActivity").contains("stopProcessesForEngineRemoval"))
        assertTrue(protocols.contains("remove-additional-engines"));assertTrue(protocols.contains("remove-all-engines"))
        assertTrue(storage.contains("confirmPendingReceipt(activity)"));assertTrue(storage.contains("Android попросит подтвердить удаление каждого пакета"))
    }
}
