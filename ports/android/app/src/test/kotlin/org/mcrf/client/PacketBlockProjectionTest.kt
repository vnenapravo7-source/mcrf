package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class PacketBlockProjectionTest {
    private fun includes(routes:List<String>,host:String)=routes.any {ClientTunCidr.parse(it).overlaps(ClientTunCidr.parse(host))}
    private fun rule(target:PacketIpRoutes.Target,cidr:String)=PacketIpRoutes.Rule(listOf(cidr),target)
    @Test fun allThreeTargetsPreserveFirstMatchWithoutDirectLeak() {
        for(first in PacketIpRoutes.Target.values())for(second in PacketIpRoutes.Target.values()) {
            val result=PacketIpRoutes.project(listOf("192.0.2.0/28"),listOf(rule(first,"192.0.2.0/30"),rule(second,"192.0.2.0/29")))
            for(i in 0..15) {
                val wanted=if(i<4)first else if(i<8)second else PacketIpRoutes.Target.VPN
                assertEquals("capture $first/$second/$i",wanted!=PacketIpRoutes.Target.DIRECT,includes(result.tun,"192.0.2.$i/32"))
                assertEquals("peer $first/$second/$i",wanted==PacketIpRoutes.Target.VPN,includes(result.peer,"192.0.2.$i/32"))
            }
        }
    }
    @Test fun blockKeepsBothFamiliesCapturedButNotForwardable() {
        val result=PacketIpRoutes.project(listOf("0.0.0.0/0","::/0"),listOf(rule(PacketIpRoutes.Target.BLOCK,"192.0.2.0/24"),rule(PacketIpRoutes.Target.BLOCK,"2001:db8::/32")))
        for(host in listOf("192.0.2.1/32","2001:db8::1/128")) {
            assertTrue(includes(result.tun,host));assertFalse(includes(result.peer,host))
        }
        assertTrue(includes(result.peer,"198.51.100.1/32"));assertTrue(includes(result.peer,"2001:db9::1/128"))
    }
    @Test fun allBlockedDoesNotInventPeerRoutesAndCannotMutateOutput() {
        val result=PacketIpRoutes.project(listOf("192.0.2.0/24"),listOf(rule(PacketIpRoutes.Target.BLOCK,"0.0.0.0/0")))
        // BLOCK outside AllowedIPs must remain captured too, otherwise it
        // escapes directly. Peer permissions must NEVER widen with TUN capture.
        assertTrue(includes(result.tun,"192.0.2.1/32"));assertTrue(includes(result.tun,"198.51.100.1/32"))
        assertFalse(includes(result.tun,"2001:db8::1/128"));assertTrue(result.peer.isEmpty())
        assertThrows(UnsupportedOperationException::class.java){(result.tun as MutableList).clear()}
        assertThrows(UnsupportedOperationException::class.java){(result.peer as MutableList).add("0.0.0.0/0")}
        assertThrows(IllegalArgumentException::class.java){PacketIpRoutes.compile(listOf("0.0.0.0/0"),listOf(rule(PacketIpRoutes.Target.BLOCK,"192.0.2.0/24")))}
    }
}
