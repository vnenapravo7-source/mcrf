package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ImportedRoutingProfileTest {
    private fun sample(kind:String)=javaClass.getResource("/routing/roscomvpn-$kind.json")!!.readText()
    @Test fun publicHappAndIncyImportOwnDnsAndDatasets() {
        for(kind in listOf("happ","incy")) {
            val parsed=ImportedRoutingProfile.parse(sample(kind));val state=RouteRules.defaults()
            val id=RoutingGroups.add(state,parsed.name,parsed.settings);RoutingGroups.select(state,id);RoutingStore.validate(state)
            assertEquals("RoscomVPN",parsed.name)
            assertTrue(GeoDataConfig.value(state).getString("geoipUrl").contains("roscomvpn-geoip"))
            assertEquals("8.8.8.8",DnsConfig.build(state.getJSONObject("globalDns"),"r","b").getString("server"))
            val advanced=state.getJSONObject("advancedDns")
            assertEquals("77.88.8.8",DnsConfig.build(advanced.getJSONObject("direct"),"d","b").getString("server"))
            assertTrue(AdvancedDnsConfig.answers(advanced.getString("answers")).has("lkfl2.nalog.ru"))
            assertTrue(GeoDataConfig.orderedRules(state).length()>10)
        }
    }
    @Test fun deepLinksForBothAppsHaveSameMeaning() {
        val json=sample("happ")
        val encoded=java.util.Base64.getEncoder().encodeToString(json.toByteArray())
        for(scheme in listOf("happ","incy"))assertEquals("RoscomVPN",ImportedRoutingProfile.parse("$scheme://routing/onadd/$encoded").name)
    }
    @Test fun dnsEndpointIpPreservesTlsIdentity() {
        val s=JSONObject().put("provider","Свой").put("protocol","DoH").put("custom","https://dns.example/dns-query").put("endpointIp","1.2.3.4")
        val server=DnsConfig.build(s,"r","b")
        assertEquals("1.2.3.4",server.getString("server"));assertEquals("dns.example",server.getJSONObject("tls").getString("server_name"))
        assertFalse(server.has("domain_resolver"))
    }
    @Test fun invalidHostsOrDnsRejectsWholeDocument() {
        val broken=JSONObject(sample("happ")).put("RemoteDNSIP","not-an-ip")
        assertThrows(IllegalArgumentException::class.java){ImportedRoutingProfile.parse(broken.toString())}
    }
    @Test fun cancellationNeverFallsBackToSavedDataset() {
        var cacheRead=false
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            GeodataRefresh.bytes({throw java.util.concurrent.CancellationException()},{cacheRead=true;byteArrayOf(1)},{})
        }
        assertFalse(cacheRead)
    }
    @Test fun failedRefreshReusesOnlyValidSavedDataset() {
        val cached=byteArrayOf(1,2)
        assertArrayEquals(cached,GeodataRefresh.bytes({error("offline")},{cached},{require(it.contentEquals(cached))}))
        assertArrayEquals(cached,GeodataRefresh.bytes({byteArrayOf(9)},{cached},{require(it.contentEquals(cached))}))
        assertThrows(IllegalStateException::class.java){GeodataRefresh.bytes({error("offline")},{null},{})}
        assertThrows(IllegalStateException::class.java){GeodataRefresh.bytes({error("offline")},{byteArrayOf(9)},{require(it.contentEquals(cached))})}
    }
}
