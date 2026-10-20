package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ConnectionDiagnosticsTest {
    @Test fun resolverStageAndDnsTransportAreReportedWithoutArbitraryNativeValues() {
        val config=JSONObject("""{"route":{"rules":[{"action":"resolve"}]},"dns":{"servers":[{"tag":"private-id","type":"https","server":"private-host"}],"final":"private-id"}}""")
        val result=ConnectionDiagnostics.summary(config)
        assertTrue(result.contains("resolve=1"));assertTrue(result.contains("по умолчанию=https"));assertFalse(result.contains("private"))
        config.getJSONObject("dns").getJSONArray("servers").getJSONObject(0).put("type","private-native-secret")
        assertFalse(ConnectionDiagnostics.summary(config).contains("private"))
    }
    @Test fun loopbackAndEndpointRulesAreNotMisreportedAsUserDirectRules() {
        val config=JSONObject("""{"outbounds":[{"type":"direct","tag":"mcrf-loopback-owned"}],"endpoints":[{"type":"wireguard","tag":"owned-endpoint"}],"route":{"final":"owned-endpoint","rules":[{"outbound":"mcrf-loopback-owned"},{"action":"route","outbound":"owned-endpoint"},{"action":"route"}]}}""")
        val result=ConnectionDiagnostics.summary(config)
        assertTrue(result.contains("напрямую=0, через движок=1, блок=0, служебные=2"))
        assertTrue(result.contains("выход по умолчанию=через движок"))
        assertFalse(result.contains("owned-endpoint"))
    }
    @Test fun countersDescribeCompiledDirectRulesWithoutLeakingAnyProfileValue() {
        val config=JSONObject("""{"outbounds":[{"tag":"private-tag","type":"direct","password":"private-password"},{"tag":"secret-proxy","type":"socks","server":"private-host"}],"route":{"final":"private-tag","rules":[{"action":"sniff"},{"action":"route","outbound":"private-tag","domain":["private.example"]},{"action":"route","outbound":"secret-proxy"},{"action":"reject"}]},"dns":{"servers":[{"tag":"private-dns","server":"private-resolver","detour":"secret-proxy"}],"rules":[]}}""")
        val result=ConnectionDiagnostics.summary(config)
        assertTrue(result.contains("напрямую=1, через движок=1, блок=1, служебные=1"))
        assertTrue(result.contains("выход по умолчанию=напрямую"))
        assertTrue(result.contains("явных detour=1"))
        for(secret in listOf("private","secret-proxy","password","private.example"))assertFalse(result.contains(secret))
        assertTrue(ConnectionDiagnostics.summary(JSONObject()).contains("иной/неизвестен"))
    }
}
