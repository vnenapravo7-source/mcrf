package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OptionalServerDnsTest {
    private fun config()=JSONObject("""{"dns":{"servers":[{"type":"local","tag":"bootstrap"}]},"route":{"final":"proxy"},"outbounds":[{"type":"vless","tag":"proxy","server":"fixture.example.test","server_port":443,"uuid":"4b72af9e-32b4-4b14-a2fc-4a9100d2eb21","tls":{"enabled":true},"domain_resolver":{"server":"bootstrap","strategy":"prefer_ipv4"}}]}""")
    private fun reject(block:()->Unit){try{block();fail("accepted lossy resolver")}catch(_:IllegalArgumentException){}}
    @Test fun xrayGeneratedResolverSurvivesConversion(){
        val config=config();val before=config.toString();val request=OptionalCorePlan(config,OptionalCoreSettings(xrayVlessDefault=true)).entries().single().request
        assertEquals("vless",request.outbound.getString("protocol"));assertEquals("local",request.serverDns!!.getJSONObject("endpoint").getString("type"))
        assertEquals("prefer_ipv4",request.serverDns!!.getString("strategy"));assertEquals(before,config.toString())
    }
    @Test fun tlsCarriesResolverOutsideOutbound(){
        val request=OptionalCorePlan(config(),OptionalCoreSettings(customFragmentDefault=true)).entries().single().request
        assertFalse(request.outbound.has("domain_resolver"));assertTrue(request.outbound.has("tls_fragment"))
        assertEquals("local",OptionalCoreConfig.privateConfig(request,10900,"fixture-user-123456","fixture-password-123456").getJSONObject("server_dns").getJSONObject("endpoint").getString("type"))
    }
    @Test fun explicitDnsAndBothFamilyStrategiesRetained(){
        for(strategy in listOf("","prefer_ipv4","prefer_ipv6","ipv4_only","ipv6_only")){
            val c=config();val out=c.getJSONArray("outbounds").getJSONObject(0)
            out.getJSONObject("domain_resolver").put("strategy",strategy)
            c.getJSONObject("dns").getJSONArray("servers").put(0,JSONObject("""{"type":"https","tag":"bootstrap","server":"1.1.1.1","server_port":8443,"path":"/fixture-dns","tls":{"enabled":true,"server_name":"fixture.example.test"}}"""))
            val captured=OptionalServerDns.capture(c,out)!!
            assertEquals(strategy,captured.getString("strategy"));assertEquals(8443,captured.getJSONObject("endpoint").getInt("server_port"))
        }
    }
    @Test fun unresolvedAndDuplicateTagsRejected(){val c=config();val out=c.getJSONArray("outbounds").getJSONObject(0);out.getJSONObject("domain_resolver").put("server","missing");reject{OptionalServerDns.capture(c,out)};out.getJSONObject("domain_resolver").put("server","bootstrap");c.getJSONObject("dns").getJSONArray("servers").put(JSONObject("""{"type":"local","tag":"bootstrap"}"""));reject{OptionalServerDns.capture(c,out)}}
    @Test fun noSilentResolverDowngrade(){for(extra in listOf("\"type\":\"quic\",\"server\":\"1.1.1.1\"","\"type\":\"https\",\"server\":\"fixture.example.test\",\"domain_resolver\":\"bootstrap\"","\"type\":\"local\",\"detour\":\"proxy\"")){val c=config();c.getJSONObject("dns").getJSONArray("servers").put(0,JSONObject("{\"tag\":\"bootstrap\",$extra}"));reject{OptionalServerDns.capture(c,c.getJSONArray("outbounds").getJSONObject(0))}}}
    @Test fun standaloneConverterCannotDropUnboundTag(){val c=config();reject{OptionalCoreConfig.prepare(c.getJSONArray("outbounds").getJSONObject(0),OptionalCoreSettings(xrayVlessDefault=true))}}
    @Test fun snapshotCannotBeChangedByCallback(){val p=OptionalCorePlan(config(),OptionalCoreSettings(xrayVlessDefault=true));p.entries().single().request.serverDns!!.put("strategy","ipv4_only");assertEquals("prefer_ipv4",p.entries().single().request.serverDns!!.getString("strategy"))}
    @Test fun unusedOptionalDoesNotRejectUnrelatedResolver(){val c=config();c.getJSONArray("outbounds").getJSONObject(0).put("domain_resolver","missing");assertFalse(OptionalCorePlan(c,OptionalCoreSettings()).requiresWorkers)}
}
