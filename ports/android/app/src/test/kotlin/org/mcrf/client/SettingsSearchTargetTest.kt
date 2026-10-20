package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class SettingsSearchTargetTest {
    @Test fun dnsIpv6UsesDnsGroupRatherThanTun(){assertEquals("advanced-dns",SettingsSearchIndex.advancedAnchor("DNS IPv6 AAAA"))}
    @Test fun tunMtuUsesTunGroup(){assertEquals("advanced-tun",SettingsSearchIndex.advancedAnchor("MTU"))}
    @Test fun tlsAndXrayAreSeparate(){assertEquals("advanced-tls",SettingsSearchIndex.advancedAnchor("TLS фрагментация"));assertEquals("advanced-xray",SettingsSearchIndex.advancedAnchor("Xray mux"))}
    @Test fun geoAndCoreHaveTheirOwnDestinations(){assertEquals("advanced-geo",SettingsSearchIndex.advancedAnchor("geosite"));assertEquals("advanced-core",SettingsSearchIndex.advancedAnchor("multiplex"))}
    @Test fun exactTextWinsOverPartialMatch(){assertEquals(2,SettingsSearchIndex.hitCount("DNS IPv6","Не выдавать IPv6-адреса через DNS VPN"));assertEquals(1,SettingsSearchIndex.hitCount("DNS IPv6","IPv6 в TUN"))}
    @Test fun normalizedSearchIgnoresCaseAndYo(){assertEquals(1,SettingsSearchIndex.hitCount("  кЭш ","Кэш ответов"));assertEquals(1,SettingsSearchIndex.hitCount("трех","Трёх"))}
}
