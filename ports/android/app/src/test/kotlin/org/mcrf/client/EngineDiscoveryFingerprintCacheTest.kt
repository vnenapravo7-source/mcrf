package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class EngineDiscoveryFingerprintCacheTest {
    private fun identity()=EngineDiscoveryFingerprintCache.Identity("fixture.engine","/fixture/engine.apk",100,1,1,1)
    private fun fingerprint()=EngineStartupPolicy.Installed(100,"01".repeat(32),1)
    @Test fun unchangedDiscoveryDoesNotRereadTheApk() {
        val cache=EngineDiscoveryFingerprintCache();var reads=0
        repeat(5){assertEquals(fingerprint(),cache.read(identity(),{true}){reads++;fingerprint()})}
        assertEquals(1,reads)
    }
    @Test fun VersionPathUpdateTimeAndBytesInvalidateDiscovery() {
        val cache=EngineDiscoveryFingerprintCache();var reads=0
        val original=identity()
        for(value in listOf(original,original.copy(version=2),original.copy(sourcePath="/fixture/reinstalled.apk"),original.copy(updated=2),original.copy(modified=2),original.copy(bytes=101))) {
            cache.read(value,{true}){reads++;fingerprint()}
        }
        assertEquals(6,reads)
    }
    @Test fun PackageChangedDuringHashIsNotCachedAndCapacityIsBounded() {
        val cache=EngineDiscoveryFingerprintCache(1);var reads=0
        try {cache.read(identity(),{false}){reads++;fingerprint()};fail("changed package accepted")}catch(_:IllegalStateException){}
        cache.read(identity(),{true}){reads++;fingerprint()}
        cache.read(identity().copy(packageName="fixture.other"),{true}){reads++;fingerprint()}
        cache.read(identity(),{true}){reads++;fingerprint()}
        assertEquals(4,reads)
    }
}
