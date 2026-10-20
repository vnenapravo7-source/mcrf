package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class EngineUpdateOpenFluxDiagnosticsTest {
    @Test fun profileShapeNeverEchoesCredentialEndpointContextOrUnknownFields(){
        val marker="PRIVATE_SENTINEL"
        val value=JSONObject().put("secret",marker).put("context",marker).put("mode",marker).put("codec",marker)
            .put("arbitrary",marker).put("transports",org.json.JSONArray().put(JSONObject().put("type",marker).put("url",marker)))
        val result=EngineUpdateOpenFluxDiagnostics.shape(value)
        assertFalse(result.toString().contains(marker));assertEquals("unknown",result.getString("mode"));assertTrue(result.getBoolean("unknown_transport"))
    }
    @Test fun probeCategoryAcceptsOnlyFixedTokensAndNeverRawExceptionText(){
        assertEquals("carrier/core: other",EngineUpdateOpenFluxDiagnostics.probeCategory("carrier/core: SecretEndpointException PRIVATE_SENTINEL"))
        assertEquals("urltest: eof,socks,timeout",EngineUpdateOpenFluxDiagnostics.probeCategory("urltest: timeout,PRIVATE_SENTINEL,eof,socks,timeout"))
        assertEquals("unclassified",EngineUpdateOpenFluxDiagnostics.probeCategory("PRIVATE_SENTINEL"))
        assertEquals("native_ready_timeout",EngineUpdateOpenFluxDiagnostics.nativeFailure(IllegalArgumentException("Движок не подключился вовремя. Проверь доступность сервиса и параметры сервера.")))
        assertEquals("carrier_or_core_unclassified",EngineUpdateOpenFluxDiagnostics.nativeFailure(IllegalStateException("PRIVATE_SENTINEL")))
    }
}
