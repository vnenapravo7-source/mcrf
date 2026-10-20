package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class PacketPeerProjectionTest {
    private val key=Base64.getEncoder().encodeToString(ByteArray(32){(it+1).toByte()})
    private val original="[Interface]\nPrivateKey=$key\nAddress=10.77.0.2/32,fd77::2/128\nDNS=10.77.0.1\n[Peer]\nPublicKey=$key\nEndpoint=192.0.2.1:51820\nAllowedIPs=10.77.0.0/24,fd77::/64\nPersistentKeepalive=25\n"
    private fun includes(routes:List<String>,ip:String)=routes.any {ClientTunCidr.parse(it).overlaps(ClientTunCidr.parse(ip))}
    @Test fun callerCannotSubstituteProfileDnsToHideBlockedResolver(){
        val rules=listOf(PacketIpRoutes.Rule(listOf("10.77.0.1/32"),PacketIpRoutes.Target.BLOCK))
        assertThrows(IllegalArgumentException::class.java){PacketPeerProjection.prepare(original,"192.0.2.1",rules,listOf("10.77.0.5"))}
    }
    @Test fun explicitlyCapturedGlobalDnsIsImmutableWhenProfileHasNone(){
        val dns=mutableListOf("10.77.0.1")
        val result=PacketPeerProjection.prepare(original.replace("DNS=10.77.0.1\n",""),"192.0.2.1",emptyList(),dns)
        dns[0]="10.77.0.5"
        assertEquals(listOf("10.77.0.1"),result.dnsServers)
        assertTrue(result.peerPlan.dns.isEmpty())
        assertThrows(UnsupportedOperationException::class.java){(result.dnsServers as MutableList).clear()}
    }
    @Test fun blockRemainsCapturedButIsRemovedFromPeerWithoutChangingProfile(){
        val rules=listOf(PacketIpRoutes.Rule(listOf("10.77.0.3/32","fd77::3/128"),PacketIpRoutes.Target.BLOCK))
        val result=PacketPeerProjection.prepare(original,"192.0.2.1",rules,listOf("10.77.0.1"))
        for(ip in listOf("10.77.0.3/32","fd77::3/128")){
            assertTrue(includes(result.tunRoutes,ip));assertFalse(includes(result.peerPlan.routes,ip))
        }
        assertTrue(includes(result.peerPlan.routes,"10.77.0.1/32"))
        assertEquals(WireGuardProfile.parse(original).fields().filterKeys {it!="AllowedIPs"},
            WireGuardProfile.parse(result.secretPeerProfile()).fields().filterKeys {it!="AllowedIPs"})
        assertFalse(result.toString().contains(key))
        assertThrows(UnsupportedOperationException::class.java){(result.tunRoutes as MutableList).clear()}
        assertTrue(result.secretPeerProfile().contains("PrivateKey = $key"))
    }
    @Test fun blockedDnsFailsClosedAndCannotBeReplacedWithPhysicalDns(){
        for(target in listOf(PacketIpRoutes.Target.BLOCK,PacketIpRoutes.Target.DIRECT)){
            assertThrows(IllegalArgumentException::class.java){PacketPeerProjection.prepare(original,"192.0.2.1",listOf(PacketIpRoutes.Rule(listOf("10.77.0.1/32"),target)),listOf("10.77.0.1"))}
        }
        assertThrows(IllegalArgumentException::class.java){PacketPeerProjection.prepare(original,"192.0.2.1",emptyList(),emptyList())}
    }
}
