package org.mcrf.client

import java.net.URI
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileBetaUpdatesTest {
    private fun asset(tag:String,engine:String?) =
        EnginePackage("arm64-v8a",tag,URI("https://github.com/owned-fixture.apk"),1,"a".repeat(64),engine)

    @Test fun staleBetaAppAndModuleRequireCurrentOptIn() {
        for(engine in listOf(null,"steer")){
            val beta=asset(MobileBetaUpdates.TAG,engine)
            assertTrue(MobileBetaUpdates.allows(beta,true))
            assertFalse(MobileBetaUpdates.allows(beta,false))
            assertFalse(MobileBetaUpdates.allows(asset("android-v2.0.0-beta.10178",engine),false))
        }
    }

    @Test fun stableAndSeparatelyPinnedPreviewOffersAreUnaffected() {
        assertTrue(MobileBetaUpdates.allows(asset("android-v2.0.0",null),false))
        assertTrue(MobileBetaUpdates.allows(asset("android-v0.1.0-preview.20261020.1","steer"),false))
    }
}
