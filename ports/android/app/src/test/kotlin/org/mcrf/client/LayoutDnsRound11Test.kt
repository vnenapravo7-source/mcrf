package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LayoutDnsRound11Test {
    @Test fun duplicateCaptchaSectionIsNotInSettingsNavigation(){
        assertFalse(ClientSettingsNavigation.CAPTCHA in ClientSettingsNavigation.children)
        assertTrue(ClientSettingsNavigation.WHITELISTS in ClientSettingsNavigation.mainTabs)
    }
    @Test fun newDirectResolverUsesSystemWithoutChangingVpnDnsDefault(){
        val defaults=AdvancedDnsConfig.defaults()
        assertEquals("Системный",defaults.getJSONObject("direct").getString("provider"))
        assertEquals("local",DnsConfig.build(defaults.getJSONObject("direct"),"d","b").getString("type"))
        assertEquals("remote",defaults.getString("final"))
        assertEquals("Cloudflare",DnsConfig.defaults().getString("provider"))
    }
    @Test fun openingLegacyResolverDoesNotResetAnExplicitChoice(){
        val old=AdvancedDnsConfig.defaults().put("direct",DnsConfig.defaults().put("provider","Google"))
        val stored=JSONObject().put("advancedDns",old)
        val visible=AdvancedDnsConfig.value(stored)
        assertEquals("Google",visible.getJSONObject("direct").getString("provider"))
        assertFalse(visible.getBoolean("enabled"))
    }
}
