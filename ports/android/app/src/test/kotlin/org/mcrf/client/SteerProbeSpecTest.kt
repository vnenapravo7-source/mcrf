package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SteerProbeSpecTest {
    @Test fun capturesHttpsNameSeparatelyFromNumericDialAndKeepsQuery() {
        val request=SteerProbeSpec("https://owned.example.test/check?a=1","192.0.2.7",44)
        assertEquals("owned.example.test",request.host);assertEquals("/check?a=1",request.path)
        assertEquals(request,SteerProbeSpec.parse(request.json().toString()))
        assertEquals("/",SteerProbeSpec("https://owned.example.test","2001:db8::7").path)
    }
    @Test fun noPlainHttpCredentialsFragmentsAlternatePortsOrHeaderInjection() {
        for(url in listOf("http://owned.test/check","https://user:pass@owned.test/check","https://owned.test/check#x",
                "https://owned.test:444/check","https://owned.test/a b","https://owned.test/a\r\nOther:yes"))
            assertThrows(IllegalArgumentException::class.java){SteerProbeSpec(url,"192.0.2.7")}
    }
    @Test fun noImplicitResolverLookupOrScopedAddressInWorkerInput() {
        for(ip in listOf("owned.test","192.0.2.7:443","fe80::1%wlan0","999.0.0.1",""))
            assertThrows(IllegalArgumentException::class.java){SteerProbeSpec("https://owned.test/check",ip)}
    }
    @Test fun strictSchemaCannotSmuggleTunTrustOrEnginePreferences() {
        val spec=SteerProbeSpec("https://owned.test/check","192.0.2.7").json()
        for(field in listOf("insecure","tun","engine","serverId"))
            assertThrows(IllegalArgumentException::class.java){SteerProbeSpec.parse(JSONObject(spec.toString()).put(field,true).toString())}
        for(timeout in listOf<Any>(true,"1000",1.5,0,30001))
            assertThrows(IllegalArgumentException::class.java){SteerProbeSpec.parse(JSONObject(spec.toString()).put("timeoutMs",timeout).toString())}
    }
}
