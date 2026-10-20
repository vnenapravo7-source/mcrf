package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class UsageFeaturesTest {
    @Test fun proxyRouteEditorHidesNewAppConditionsButRetainsExistingRepairPaths() {
        val all=UsageFeatures.Feature.values()
        val modes=listOf("any","addresses","apps","in-apps","except-apps")
        for(mask in 1 until (1 shl all.size)){
            val selected=all.filterIndexed {index,_->mask and (1 shl index)!=0}.toSet()
            for(configured in modes){
                val visible=UsageFeatures.routeModes(selected,configured)
                assertEquals(if(mask==2)modes.filter {it=="any"||it=="addresses"||it==configured} else modes,visible)
                assertTrue(configured in visible)
                assertEquals("addresses",visible[visible.indexOf("addresses")]) // Preset selection uses filtered, not original indices.
            }
            assertEquals(mask!=2,UsageFeatures.showRouteApps(selected))
            assertTrue(UsageFeatures.showRouteApps(selected,configured=true))
            assertEquals(mask!=2,UsageFeatures.showGlobalAppPolicies(selected))
            assertTrue(UsageFeatures.showGlobalAppPolicies(selected,configured=true))
            assertEquals(if(mask==2)"Прокси" else "VPN",UsageFeatures.routedTrafficLabel(selected))
        }
    }
    @Test fun tunOnlyAdvancedControlsAndAddonFollowAllFeatureChoices() {
        val features=UsageFeatures.Feature.values()
        for(mask in 1 until (1 shl features.size)){
            val chosen=features.filterIndexed {index,_->mask and (1 shl index)!=0}.toSet()
            assertEquals(mask!=2,UsageFeatures.showFakeIp(chosen))
            assertEquals(mask and 2!=0&&mask!=2,UsageFeatures.showAndroidHttpProxy(chosen))
            assertEquals(mask!=2,UsageFeatures.engine("wg-awg",chosen))
            assertTrue(UsageFeatures.showFakeIp(chosen,configured=true)) // Never hide the only way to repair a blocking saved VPN option.
            assertTrue(UsageFeatures.showAndroidHttpProxy(chosen,configured=true))
        }
    }
    @Test fun protocolCatalogueUsesTheSameFeatureSelectionWithoutRemovingProxyUpstreams() {
        val features=UsageFeatures.Feature.values()
        for(mask in 1 until (1 shl features.size)){
            val chosen=features.filterIndexed {index,_->mask and (1 shl index)!=0}.toSet()
            assertEquals(mask and 3!=0,UsageFeatures.protocol("VLESS","sing-box-lx",chosen))
            assertEquals(mask and 7!=0,UsageFeatures.protocol("WireGuard / AmneziaWG","sing-box-lx",chosen))
            assertEquals(mask and 8!=0,UsageFeatures.protocol("CSQTT / WDTT / QWDTT","separate",chosen))
            assertEquals(mask and 10!=0,UsageFeatures.protocol("Telegram WS","separate",chosen))
            assertEquals(mask and 10!=0,UsageFeatures.engine("tgws",chosen))
        }
    }
    @Test fun oldInstallationsKeepAllControlsAndUnknownDataDoesNotHideEverything() {
        assertEquals(UsageFeatures.all,UsageFeatures.decode(null))
        assertEquals(UsageFeatures.all,UsageFeatures.decode("future-feature"))
    }
    @Test fun allNonEmptyCombinationsRoundTripInStableOrder() {
        val features=UsageFeatures.Feature.values()
        for(mask in 1 until (1 shl features.size)) {
            val chosen=features.filterIndexed {index,_->mask and (1 shl index)!=0}.toSet()
            assertEquals(chosen,UsageFeatures.decode(UsageFeatures.encode(chosen)))
            assertEquals(UsageFeatures.encode(chosen),UsageFeatures.encode(chosen.reversed().toSet()))
        }
        assertThrows(IllegalArgumentException::class.java){UsageFeatures.encode(emptySet())}
    }
    @Test fun filteringAlwaysLeavesAccessToChoiceAndGeneralSafetySettings() {
        val warp=setOf(UsageFeatures.Feature.WARP)
        assertFalse(UsageFeatures.visible(ClientSettingsNavigation.VK,warp))
        assertFalse(UsageFeatures.visible(ClientSettingsNavigation.XRAY,warp))
        assertFalse(UsageFeatures.visible(ClientSettingsNavigation.TLS,warp))
        assertFalse(UsageFeatures.visible(ClientSettingsNavigation.SHARING,warp))
        for(page in listOf(ClientSettingsNavigation.ROOT,ClientSettingsNavigation.UPDATES,ClientSettingsNavigation.BEHAVIOR,ClientSettingsNavigation.DNS))assertTrue(UsageFeatures.visible(page,warp))
        assertTrue(UsageFeatures.visible(ClientSettingsNavigation.VK,setOf(UsageFeatures.Feature.WHITELIST)))
        assertFalse(UsageFeatures.visible(ClientSettingsNavigation.SHARING,setOf(UsageFeatures.Feature.VPN)))
        assertTrue(UsageFeatures.visible(ClientSettingsNavigation.SHARING,setOf(UsageFeatures.Feature.PROXY)))
        assertFalse(UsageFeatures.usesVpn(setOf(UsageFeatures.Feature.PROXY)))
        assertTrue(UsageFeatures.usesVpn(warp))
    }
    @Test fun onboardingOnlyForFreshUnansweredInstall() {
        assertTrue(UsageFeatures.needsFirstChoice(null,false,true))
        assertFalse(UsageFeatures.needsFirstChoice(null,true,true))
        assertFalse(UsageFeatures.needsFirstChoice(null,false,false))
        assertFalse(UsageFeatures.needsFirstChoice("vpn",false,true))
    }
    @Test fun navigationAndHomeModeFollowAllFeatureCombinations(){
        val all=UsageFeatures.Feature.values()
        for(mask in 1 until (1 shl all.size)){
            val selected=all.filterIndexed {index,_->mask and (1 shl index)!=0}.toSet()
            assertEquals(UsageFeatures.Feature.VPN in selected||UsageFeatures.whitelists(selected),UsageFeatures.visible("Автодеплой",selected))
            assertEquals(UsageFeatures.whitelists(selected),UsageFeatures.visible(ClientSettingsNavigation.WHITELISTS,selected))
            assertEquals(UsageFeatures.Feature.WHITELIST in selected,UsageFeatures.visible(ClientSettingsNavigation.CAPTCHA,selected))
            assertEquals(UsageFeatures.Feature.WARP in selected,UsageFeatures.visible("WARP",selected))
            assertTrue(UsageFeatures.visible("Маршрутизация",selected)) // Domain/IP rules also apply to local proxy traffic.
            assertEquals(UsageFeatures.Feature.PROXY in selected&&UsageFeatures.usesVpn(selected),UsageFeatures.showModeSwitch(selected))
            assertTrue(UsageFeatures.visible(ClientSettingsNavigation.ROOT,selected))
            assertEquals(mask and 10!=0,UsageFeatures.telegram(selected))
            assertTrue(UsageFeatures.telegram(selected,wanted=true))
            assertTrue(UsageFeatures.telegram(selected,running=true))
            for(stored in listOf(false,true)){
                val expected=if(UsageFeatures.Feature.PROXY !in selected)true else if(!UsageFeatures.usesVpn(selected))false else stored
                assertEquals(expected,UsageFeatures.preferredVpn(selected,stored))
            }
        }
    }
}
