package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONArray

class EngineUpdatePrimaryTest {
    private fun valid(kind:String="steer",spi:Int=1,api:Int=2,entry:String="org.mcrf.engines.steer.SteerEngine",hash:String="a".repeat(64),abis:Set<String> = setOf("arm64-v8a"),abi:String="arm64-v8a")=
        EngineUpdatePrimary.validate(kind,EngineUpdatePrimary.contract(kind).packageName,spi,api,entry,hash,abis,abi)
    private fun rejected(block:()->Unit){try{block();fail("Unsafe primary contract accepted")}catch(_:IllegalArgumentException){}}
    @Test fun twoPrimaryIdentitiesAreSeparateFromTransportWorkers(){
        assertEquals(setOf("sing-box","steer"),EngineModules.primaryCores)
        assertFalse("sing-box" in EngineModules.kinds);assertFalse("steer" in EngineModules.optionalCores)
        assertEquals("org.mcrf.engines.singbox",EngineModules.packageName("sing-box"))
        assertEquals("steer",EngineModules.kind("org.mcrf.engines.steer"))
        assertEquals("singbox",EngineUpdatePrimary.choiceId("sing-box"))
        assertEquals("sing-box",EngineUpdatePrimary.kindForChoice("singbox"))
        EngineUpdatePrimary.kinds.forEach{assertEquals(it,EngineUpdatePrimary.kindForChoice(EngineUpdatePrimary.choiceId(it)))}
        rejected{EngineUpdatePrimary.kindForChoice("sing-box")}
    }
    @Test fun spiApiEntryAndProvenanceMismatchFailBeforeLoading(){
        valid();rejected{valid(spi=2)};rejected{valid(api=1)};rejected{valid(entry="attacker.Entry")};rejected{valid(hash="")}
    }
    @Test fun unsupportedAbiNeverFallsBackToAnotherCore(){
        rejected{valid(abis=setOf("armeabi-v7a"),abi="armeabi-v7a")}
        rejected{valid(abis=setOf("arm64-v8a"),abi="x86_64")}
        assertTrue(EngineUpdatePrimary.validNativeEntries("steer","arm64-v8a",listOf("lib/arm64-v8a/libsteer_vpn.so")))
        assertFalse(EngineUpdatePrimary.validNativeEntries("steer","arm64-v8a",listOf("lib/arm64-v8a/libsteer_vpn.so","lib/arm64-v8a/libgojni.so")))
    }
    @Test fun noPublishedAssetDoesNotInventAnInstallUrl(){
        assertEquals("mcrf-engine-sing-box-arm64-v8a-release.apk",EngineModules.assetName("sing-box","arm64-v8a"))
        try{EnginePackage.find(JSONArray(),"arm64-v8a","steer");fail()}catch(_:EnginePackage.Unpublished){}
    }
    @Test fun steerHelperRequiresTheExactPairForEveryShippedAbi(){
        val pair=listOf("libsteer_vpn.so","libmcrf_utls49.so")
        val both=listOf("arm64-v8a","x86_64").flatMap{abi->pair.map{"lib/$abi/$it"}}
        for(abi in listOf("arm64-v8a","x86_64")){
            val single=pair.map{"lib/$abi/$it"}
            assertTrue(EngineUpdatePrimary.validNativeEntries("steer",abi,single))
            assertTrue(EngineUpdatePrimary.validNativeEntries("steer",abi,both))
            assertFalse(EngineUpdatePrimary.validNativeEntries("steer",abi,single.drop(1)))
            assertFalse(EngineUpdatePrimary.validNativeEntries("steer",abi,single+single.first()))
        }
        assertFalse(EngineUpdatePrimary.validNativeEntries("steer","arm64-v8a",both.dropLast(1)))
        assertFalse(EngineUpdatePrimary.validNativeEntries("steer","armeabi-v7a",both))
    }
    @Test fun helperCannotAuthorizeOtherLibrariesOrOtherEngines(){
        val pair=listOf("lib/arm64-v8a/libsteer_vpn.so","lib/arm64-v8a/libmcrf_utls49.so")
        assertFalse(EngineUpdatePrimary.validNativeEntries("steer","arm64-v8a",pair+"lib/arm64-v8a/other.so"))
        assertFalse(EngineUpdatePrimary.validNativeEntries("sing-box","arm64-v8a",pair))
        assertTrue(EngineUpdatePrimary.validNativeEntries("sing-box","arm64-v8a",listOf("lib/arm64-v8a/libbox.so")))
        assertFalse(EngineUpdatePrimary.validNativeEntries("sing-box","arm64-v8a",listOf("lib/arm64-v8a/libbox.so",pair.last())))
    }
    @Test fun confirmedSwitchRejectsMissingModuleBusyRuntimeAndProfileCapabilities(){
        rejected{EngineUpdatePrimaryUi.requireSwitch("steer",false,false,emptyList())}
        rejected{EngineUpdatePrimaryUi.requireSwitch("steer",true,true,emptyList())}
        rejected{EngineUpdatePrimaryUi.requireSwitch("steer",true,false,listOf("IPv6 not supported"))}
        EngineUpdatePrimaryUi.requireSwitch("sing-box",true,false,emptyList())
    }
    @Test fun updatesExposeOnePhaseAppropriateAction(){
        assertEquals(EngineUpdatePresentation.Action.DOWNLOAD,EngineUpdatePresentation.action(EngineUpdatePolicy.Phase.AVAILABLE))
        assertEquals(EngineUpdatePresentation.Action.INSTALL,EngineUpdatePresentation.action(EngineUpdatePolicy.Phase.QUEUED))
        assertEquals(EngineUpdatePresentation.Action.CANCEL,EngineUpdatePresentation.action(EngineUpdatePolicy.Phase.DOWNLOADING))
        assertEquals(EngineUpdatePresentation.Action.WAIT,EngineUpdatePresentation.action(EngineUpdatePolicy.Phase.INSTALLING))
        assertEquals(EngineUpdatePresentation.Action.CHECK,EngineUpdatePresentation.action(EngineUpdatePolicy.Phase.CURRENT))
    }
}
