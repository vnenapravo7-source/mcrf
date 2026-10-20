package org.mcrf.client

import java.net.URI
import org.junit.Assert.*
import org.junit.Test

class EngineOfferRevalidationTest {
    private val hash="a".repeat(64)
    private fun offer(kind:String="wdtt",version:Long?=5)=EnginePackage("arm64-v8a","test",
        URI("https://github.com/${EnginePackage.REPOSITORY}/releases/download/${EnginePackage.CATALOGUE_RELEASE}/${EngineModules.assetName(kind,"arm64-v8a")}"),42,hash,kind,version)
    private fun local(version:Long=4,digest:String="b".repeat(64),bytes:Long=41)=
        EngineStartupPolicy.Installed(bytes,digest,version)

    @Test fun removedModuleDoesNotBecomeNewInstall(){
        assertTrue(EngineStartupPolicy.stillNeeded(listOf(offer()),emptyMap()).isEmpty())
    }
    @Test fun newerLocalInstalledAfterDiscoveryIsNotDowngraded(){
        assertTrue(EngineStartupPolicy.stillNeeded(listOf(offer()),mapOf("wdtt" to local(6))).isEmpty())
    }
    @Test fun alreadyInstalledExactBytesAreNotOfferedAgain(){
        assertTrue(EngineStartupPolicy.stillNeeded(listOf(offer()),mapOf("wdtt" to local(5,hash,42))).isEmpty())
    }
    @Test fun changedSameVersionAndOlderDedicatedAndLegacyStillOffer(){
        for(version in listOf(0L,4L,5L)) {
            val asset=offer()
            assertEquals(listOf(asset),EngineStartupPolicy.stillNeeded(listOf(asset),mapOf("wdtt" to local(version))))
        }
    }
    @Test fun unknownRemoteVersionIsNotAnUpdate(){
        assertTrue(EngineStartupPolicy.stillNeeded(listOf(offer(version=null)),mapOf("wdtt" to local())).isEmpty())
    }
    @Test fun mixedOffersKeepOnlyCurrentInstalledEligibleKinds(){
        val old=offer();val absent=offer("qwdtt");val newer=offer("csqtt");val current=offer("csqtt25")
        assertEquals(listOf(old),EngineStartupPolicy.stillNeeded(listOf(old,absent,newer,current),mapOf(
            "wdtt" to local(),"csqtt" to local(6),"csqtt25" to local(5,hash,42))))
    }
}
