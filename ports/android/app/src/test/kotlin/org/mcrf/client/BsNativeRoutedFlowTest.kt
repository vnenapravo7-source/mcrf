package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BsNativeRoutedFlowTest {
    private fun row()=JSONObject().put("contract","BS_NATIVE_FLOW_OBSERVATION_CONTRACT_1").put("generation",42L).put("protocol",6)
        .put("source","198.18.0.2").put("source_port",40001).put("destination","192.0.2.1").put("destination_port",443)
        .put("uid",10001).put("domain","Example.ru").put("target","proxy")
    @Test fun proxyAndDirectAreDistinctAndDomainIsCanonical(){
        val proxy=BsNativeRoutedFlow.parse(row(),42);assertTrue(proxy.tunneled);assertEquals("example.ru",proxy.domain)
        val direct=BsNativeRoutedFlow.parse(row().put("target","direct").put("domain",JSONObject.NULL).put("protocol",17),42)
        assertFalse(direct.tunneled);assertNull(direct.domain);assertEquals(17,direct.query.protocol)
    }
    @Test fun staleGenerationWrongUidInvalidAddressAndTargetAreRejected(){
        val invalid=listOf(row().put("generation",43),row().put("uid",-1),row().put("source","example.com"),row().put("destination_port",0),row().put("target","unknown"),row().put("domain","bad host"),row().put("uid","10001"),row().put("extra",true))
        invalid.forEach {try{BsNativeRoutedFlow.parse(it,42);fail()}catch(_:IllegalArgumentException){}}
    }
}
