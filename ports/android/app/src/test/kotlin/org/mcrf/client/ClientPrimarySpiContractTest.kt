package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test
import org.mcrf.engine.spi.v1.Engine
import org.mcrf.engine.spi.v1.Platform

/** Uses the actual pinned parent contract, not another copy of module DTOs. */
class ClientPrimarySpiContractTest {
    private val profile="test-profile"
    private val settings="{\"schema\":1}"
    private val capability="ab".repeat(32)
    @Test fun choiceAndManifestIdentitiesAreNotInterchangeable() {
        assertEquals("sing-box",Engine.Kind.fromChoiceId("singbox").manifestKind)
        assertEquals("org.mcrf.engines.steer",Engine.Kind.fromChoiceId("steer").packageName)
        assertThrows(IllegalArgumentException::class.java){Engine.Kind.fromChoiceId("sing-box")}
    }
    @Test fun proxyValidationIsBoundToExactImmutableInputs() {
        val validated=Engine.Validation.proxyValidated(profile,settings)
        fun request(value:String)=Engine.Request(1,-1,value,profile,"127.0.0.1","private-fixture",0,emptyArray(),Engine.Mode.PROXY,validated)
        assertEquals(Engine.TunAcquisition.NONE,request(settings).tunAcquisition)
        assertThrows(IllegalArgumentException::class.java){request("{}")}
    }
    @Test fun deferredVpnRequiresMatchingHostCapabilityAndNoPlaceholderFd() {
        val validated=Engine.Validation.deferredVpnValidated(profile,settings,capability)
        fun request(fd:Int,pin:String?)=Engine.Request(1,fd,settings,profile,"127.0.0.1","private-fixture",0,emptyArray(),Engine.Mode.VPN,validated,Engine.TunAcquisition.DEFERRED,pin)
        assertEquals(Engine.TunAcquisition.DEFERRED,request(-1,capability).tunAcquisition)
        assertThrows(IllegalArgumentException::class.java){request(0,capability)}
        assertThrows(IllegalArgumentException::class.java){request(-1,null)}
        assertThrows(IllegalArgumentException::class.java){request(-1,"cd".repeat(32))}
    }
    @Test fun unavailableCounterDoesNotBecomeObservedZero() {
        val partial=Engine.Stats(Engine.Metric.of(0),Engine.Metric.of(12),Engine.Metric.unavailable())
        assertTrue(partial.anyAvailable());assertFalse(partial.allAvailable())
        assertEquals(0L,partial.txPayload.value);assertNull(partial.blockedAttempts.value)
        assertEquals("STATS_PARTIAL",Engine.ControlReply.forStats(partial).code)
        assertNull(Engine.ControlReply.forStats(partial).counters)
    }
    @Test fun dnsPacketAndBootstrapCopiesCannotBeMutatedByCaller() {
        val source=ByteArray(12);val query=Platform.DnsQuery(1,source,8000)
        source[0]=9;assertEquals(0,query.packet()[0].toInt())
        query.packet()[0]=8;assertEquals(0,query.packet()[0].toInt())
        val dns=arrayOf("192.0.2.1")
        val request=Engine.Request(1,4,settings,profile,"127.0.0.1","private-fixture",0,dns)
        dns[0]="192.0.2.2";assertEquals("192.0.2.1",request.dnsBootstrapIpv4()[0])
    }
    @Test fun routeListsAreImmutableAndIpv6CannotUseUndersizedMtu() {
        val addresses=mutableListOf("fd00::1/126")
        val routes=Platform.Routes(emptyList(),addresses,emptyList(),emptyList(),emptyList(),emptyList(),emptyList(),emptyList())
        addresses.clear();assertEquals(1,routes.inet6Address.size)
        assertThrows(UnsupportedOperationException::class.java){routes.inet6Address.clear()}
        assertThrows(IllegalArgumentException::class.java){Platform.TunPlan(576,true,true,false,routes,emptyList(),emptyList(),emptyList(),Platform.HttpProxy(false,"",0,emptyList()))}
    }
}
