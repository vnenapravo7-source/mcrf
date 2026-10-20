package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

class LanSharingAddressesTest {
    private fun allowed(name:String,ip:String,up:Boolean=true,loop:Boolean=false)=LanSharingAddresses.allowed(name,up,loop,InetAddress.getByName(ip))
    @Test fun showsWifiAndHotspotPrivateIpv4(){assertTrue(allowed("wlan0","192.168.7.234"));assertTrue(allowed("ap0","192.168.43.1"));assertTrue(allowed("eth0","10.1.2.3"))}
    @Test fun excludesVpnAndCellularEvenWithPrivateAddresses(){for(name in listOf("tun0","wg0","rmnet_data0","ccmni0","pdp0"))assertFalse(allowed(name,"10.1.2.3"))}
    @Test fun excludesPublicIpv6LoopbackAndDownInterfaces(){assertFalse(allowed("wlan0","8.8.8.8"));assertFalse(allowed("wlan0","fd00::1"));assertFalse(allowed("lo","127.0.0.1"));assertFalse(allowed("wlan0","192.168.1.1",false));assertFalse(allowed("wlan0","192.168.1.1",loop=true))}
}
