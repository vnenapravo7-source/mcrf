package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class PacketTunSnapshotTest {
    private fun plan():CompactWireGuardPlan.Plan {
        val key=Base64.getEncoder().encodeToString(ByteArray(32){1})
        return CompactWireGuardPlan.prepare(WireGuardProfile.parse("[Interface]\nPrivateKey=$key\nAddress=10.8.0.2/32,fd08::2/128\nDNS=10.8.0.1\nMTU=1400\n[Peer]\nPublicKey=$key\nEndpoint=192.0.2.1:51820\nAllowedIPs=0.0.0.0/0,::/0"),"192.0.2.1")
    }
    private fun check(tun:ClientTunSettings=ClientTunSettings(mixedEnabled=false),core:CoreTuningSettings=CoreTuningSettings()) {
        val p=plan();PacketTunSnapshot.validate(p,PacketRoutingSnapshot.capture(RouteRules.defaults(),p),tun,core)
    }
    @Test fun acceptsExplicitCompatibleTunWithoutModifyingSettings(){val tun=ClientTunSettings(mixedEnabled=false);val before=tun.json().toString();check(tun);assertEquals(before,tun.json().toString())}
    @Test fun refusesRequestedListeners(){for(tun in listOf(ClientTunSettings(),ClientTunSettings(mixedEnabled=false,shareLan=true),ClientTunSettings(mixedEnabled=false,dnsPort=5533)))assertThrows(IllegalArgumentException::class.java){check(tun)}}
    @Test fun refusesIgnoredTunOverrides(){for(tun in listOf(ClientTunSettings(mixedEnabled=false,bypassPrivate=true),ClientTunSettings(mixedEnabled=false,bypassDirectIps=true),ClientTunSettings(mixedEnabled=false,mtu=1500),ClientTunSettings(mixedEnabled=false,ipv6=false),ClientTunSettings(mixedEnabled=false,address4="10.99.0.2/32")))assertThrows(IllegalArgumentException::class.java){check(tun)}}
    @Test fun rejectsActiveControllerAndClockServices(){for(core in listOf(CoreTuningSettings(apiEnabled=true,apiSecret="a".repeat(16)),CoreTuningSettings(clashEnabled=true,clashSecret="a".repeat(16)),CoreTuningSettings(ntpEnabled=true)))assertThrows(IllegalArgumentException::class.java){check(core=core)}}
}
