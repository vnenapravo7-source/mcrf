package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

/** New presentation boundaries, not grants to execute a disabled/unsupported engine. */
class UsageFeaturesSeparateBsTest {
    @Test fun legacyWhitelistChoiceKeepsOpenFluxButDoesNotInventS3Selection(){
        val old=UsageFeatures.decode("whitelist")
        assertTrue(UsageFeatures.Feature.WHITELIST in old)
        assertTrue(UsageFeatures.Feature.OPENFLUX in old)
        assertFalse(UsageFeatures.Feature.S3_BYPASS in old)
    }
    @Test fun v2SelectionsSeparateEachBsFamilyAndHideVpnHome(){
        for(feature in listOf(UsageFeatures.Feature.WHITELIST,UsageFeatures.Feature.OPENFLUX,UsageFeatures.Feature.S3_BYPASS)){
            val chosen=setOf(feature)
            assertEquals(chosen,UsageFeatures.decode(UsageFeatures.encode(chosen)))
            assertFalse(UsageFeatures.visible("Главная",chosen))
            assertFalse(UsageFeatures.protocol("VLESS","sing-box-lx",chosen))
            assertTrue(UsageFeatures.visible(ClientSettingsNavigation.WHITELISTS,chosen))
            assertEquals(feature==UsageFeatures.Feature.OPENFLUX,UsageFeatures.engine("openflux",chosen))
            assertEquals(feature==UsageFeatures.Feature.S3_BYPASS,UsageFeatures.engine(S3BypassProfile.KIND,chosen))
            assertEquals(feature==UsageFeatures.Feature.WHITELIST,UsageFeatures.engine("csqtt",chosen))
            assertEquals(feature==UsageFeatures.Feature.S3_BYPASS,UsageFeatures.deployRecipe(S3BypassProfile.KIND,chosen))
        }
    }
    @Test fun warpOnlyKeepsVpnConnectionSurfaceWithoutUnrelatedProtocolRows(){
        val chosen=setOf(UsageFeatures.Feature.WARP)
        assertTrue(UsageFeatures.visible("Главная",chosen))
        assertTrue(UsageFeatures.protocol("WireGuard / AmneziaWG","sing-box-lx",chosen))
        assertFalse(UsageFeatures.protocol("VLESS","sing-box-lx",chosen))
        assertFalse(UsageFeatures.visible(ClientSettingsNavigation.WHITELISTS,chosen))
    }
}
