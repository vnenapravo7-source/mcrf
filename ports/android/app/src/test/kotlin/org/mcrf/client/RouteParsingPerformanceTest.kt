package org.mcrf.client
import org.junit.Assert.*
import org.junit.Test

class RouteParsingPerformanceTest {
    @Test fun largeListWarmParseReusesImmutableContentAndEditsInvalidate(){
        val text=(1..12_000).joinToString("\n"){"IP-CIDR,10.${it/256}.${it%256}.0/24"}+"\nexample.invalid"
        val start=System.nanoTime();val cold=RouteRules.parse(text);val coldNs=System.nanoTime()-start
        val warmStart=System.nanoTime();val warm=RouteRules.parse(text);val warmNs=System.nanoTime()-warmStart
        assertSame(cold,warm);assertEquals(12_000,cold.getValue("ip_cidr").size)
        assertEquals(listOf("example.invalid"),cold.getValue("domain_suffix"))
        val changed=RouteRules.parse(text+"\nnew.invalid")
        assertNotSame(cold,changed);assertEquals(2,changed.getValue("domain_suffix").size)
        println("HOST_ONLY_route_parse cold_us=${coldNs/1000} warm_us=${warmNs/1000}; not Android connection latency")
    }
}
