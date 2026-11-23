package org.mcrf.client

import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuAppTunnelNoticeTest {
    private fun config(rules:String,vararg members:String)=JSONObject("""
        {"route":{"final":"pool","rules":$rules},"outbounds":[
          {"tag":"pool","type":"selector","outbounds":${JSONArray(members.toList())}},
          {"tag":"remote","type":"vless","server":"example.com"},
          {"tag":"direct","type":"direct"}]}
    """.trimIndent())

    @Test fun onlyUnambiguousRemoteRoutingQualifies(){
        assertTrue(RuAppTunnelNotice.unambiguousProxy(config("""[{"action":"sniff"},{"action":"hijack-dns","port":53}]""","remote")))
        assertFalse(RuAppTunnelNotice.unambiguousProxy(config("""[{"outbound":"direct","port":443}]""","remote")))
        assertFalse(RuAppTunnelNotice.unambiguousProxy(config("[]","remote","direct")))
        assertFalse(RuAppTunnelNotice.unambiguousProxy(config("[]","pool")))
    }
    @Test fun dnsHijackDoesNotSuppressWebTrafficWithoutACachedHostname(){
        val rules = """[{"action":"sniff"},{"action":"hijack-dns","protocol":"dns"}]"""
        val flow = RuAppTunnelNotice.Flow(setOf("ru.ozon.fintech.finance"), null, "203.0.113.10", 443, 6)
        assertTrue(RuAppTunnelNotice.unambiguousProxy(config(rules, "remote"), flow))
        assertTrue(RuAppTunnelNotice.unambiguousProxy(config(rules, "remote"), flow.copy(protocol = 17)))
        assertFalse(RuAppTunnelNotice.unambiguousProxy(config(rules, "remote"), flow.copy(port = 53)))
    }

    @Test fun mixedRoutingHonorsDirectRulesAndRecognizesBrowserDomains(){
        val flow=RuAppTunnelNotice.Flow(setOf("com.android.chrome"),"max.ru","203.0.113.10",443,6)
        assertTrue(RuAppTunnelNotice.russianDomain(flow.host))
        assertTrue(RuAppTunnelNotice.russianDomain("mail.yandex.ru"))
        assertFalse(RuAppTunnelNotice.russianDomain("yandex.ru.example.com"))
        val mixed=config("""[{"outbound":"direct","domain_suffix":["example.com"]}]""","remote")
        assertTrue(RuAppTunnelNotice.unambiguousProxy(mixed,flow))
        val direct=config("""[{"outbound":"direct","domain_suffix":["max.ru"]}]""","remote")
        assertFalse(RuAppTunnelNotice.unambiguousProxy(direct,flow))
        val opaque=config("""[{"outbound":"direct","rule_set":["rules"]}]""","remote")
        assertFalse(RuAppTunnelNotice.unambiguousProxy(opaque,flow))
    }

}
