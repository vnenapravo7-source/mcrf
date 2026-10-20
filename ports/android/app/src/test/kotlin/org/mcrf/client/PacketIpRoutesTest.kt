package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject

class PacketIpRoutesTest {
    private fun rule(target:PacketIpRoutes.Target,vararg cidrs:String)=PacketIpRoutes.Rule(cidrs.toList(),target)
    private val vpn=PacketIpRoutes.Target.VPN
    private val direct=PacketIpRoutes.Target.DIRECT
    private fun includes(routes:List<String>,ip:String)=routes.map {ClientTunCidr.parse(it)}.any {it.overlaps(ClientTunCidr.parse(ip))}
    private fun ip(vararg cidrs:String)=JSONObject().put("ip_cidr",JSONArray(cidrs.toList()))
    private fun logical(mode:String,vararg children:JSONObject)=JSONObject().put("type","logical").put("mode",mode).put("rules",JSONArray(children.toList()))
    @Test fun nestedIpExpressionsKeepBothFamiliesAndDoNotMutateInput(){
        val expression=logical("and",logical("or",ip("192.0.2.0/24"),ip("2001:db8::/32")),ip("192.0.2.64/26").put("invert",true))
        val before=expression.toString();val result=PacketIpRoutes.Conditions().compile(expression)
        assertTrue(includes(result,"192.0.2.1/32"));assertFalse(includes(result,"192.0.2.70/32"))
        assertTrue(includes(result,"2001:db8::5/128"));assertFalse(includes(result,"2001:db9::5/128"))
        assertEquals(before,expression.toString())
        val inverse=PacketIpRoutes.Conditions().compile(expression,true)
        assertFalse(includes(inverse,"192.0.2.1/32"));assertTrue(includes(inverse,"192.0.2.70/32"))
        assertFalse(includes(inverse,"2001:db8::5/128"));assertTrue(includes(inverse,"2001:db9::5/128"))
        assertThrows(UnsupportedOperationException::class.java){(result as MutableList).clear()}
    }
    @Test fun ipExpressionsNeverShortCircuitAwayUnsupportedConditions(){
        for(bad in listOf(JSONObject().put("domain","owned.test"),ip("192.0.2.0/24").put("port",443),
            ip("192.0.2.0/24").put("package_name","android"),ip("192.0.2.0/24").put("invert","true"),
            JSONObject().put("type","default").put("ip_cidr",JSONArray().put(7)))){
            val expression=logical("and",ip("192.0.2.0/24"),ip("2001:db8::/32"),bad)
            assertThrows(IllegalArgumentException::class.java){PacketIpRoutes.Conditions().compile(expression)}
        }
        val empty=PacketIpRoutes.Conditions().compile(logical("and",ip("192.0.2.0/24"),ip("2001:db8::/32")))
        assertTrue(empty.isEmpty()) // false predicate, NEVER implicit default route
    }
    @Test fun ipExpressionBudgetsAreSharedAcrossAllRules(){
        val shared=PacketIpRoutes.Conditions()
        repeat(4096){shared.compile(ip("192.0.2.0/24"))}
        assertThrows(IllegalArgumentException::class.java){shared.compile(ip("192.0.2.0/24"))}
        var nested=ip("192.0.2.0/24");repeat(17){nested=logical("or",nested)}
        assertThrows(IllegalArgumentException::class.java){PacketIpRoutes.Conditions().compile(nested)}
    }
    @Test fun preservesFirstMatchForEveryAddressInSmallSubnet(){
        for(first in listOf(vpn,direct))for(second in listOf(vpn,direct)) {
            val rules=listOf(rule(first,"192.0.2.0/30"),rule(second,"192.0.2.0/29"),rule(direct,"192.0.2.0/28"))
            val result=PacketIpRoutes.compile(listOf("192.0.2.0/28"),rules)
            for(i in 0..15){val target=if(i<4)first else if(i<8)second else direct;assertEquals("host $i $rules",target==vpn,includes(result,"192.0.2.$i/32"))}
            assertFalse(includes(result,"192.0.2.16/32"))
        }
    }
    @Test fun keepsIpv4AndIpv6Independent(){
        val result=PacketIpRoutes.compile(listOf("192.0.2.0/24","2001:db8::/32"),listOf(rule(direct,"192.0.2.128/25","2001:db8:1::/48")))
        assertTrue(includes(result,"192.0.2.1/32"));assertFalse(includes(result,"192.0.2.200/32"))
        assertTrue(includes(result,"2001:db8:2::1/128"));assertFalse(includes(result,"2001:db8:1::1/128"))
    }
    @Test fun blockIsNeverTranslatedIntoDirectLeak(){
        assertThrows(IllegalArgumentException::class.java){PacketIpRoutes.compile(listOf("0.0.0.0/0"),listOf(rule(PacketIpRoutes.Target.BLOCK,"192.0.2.0/24")))}
    }
    @Test fun boundsSubtractionWorkRatherThanHangingOnLargeRuleSets(){
        val allowed=(0..511).map {"10.0.${it/256}.${it%256}/32"}
        val rules=(0..255).map {rule(direct,"10.0.0.$it/32")}
        val error=assertThrows(IllegalArgumentException::class.java){PacketIpRoutes.compile(allowed,rules)}
        assertTrue(error.message.orEmpty().contains("Слишком сложные"))
    }
    @Test fun immutableBoundedAndNumericOnly(){
        val result=PacketIpRoutes.compile(listOf("192.0.2.0/24"),emptyList())
        assertThrows(UnsupportedOperationException::class.java){(result as MutableList).clear()}
        for(bad in listOf("example.org/32","0.0.0.0/33","fe80::1%wlan0/128"))assertThrows(IllegalArgumentException::class.java){PacketIpRoutes.compile(listOf(bad),emptyList())}
        assertThrows(IllegalArgumentException::class.java){PacketIpRoutes.compile(emptyList(),emptyList())}
        assertThrows(IllegalArgumentException::class.java){PacketIpRoutes.compile(List(513){"0.0.0.0/0"},emptyList())}
        assertThrows(IllegalArgumentException::class.java){PacketIpRoutes.compile(listOf("0.0.0.0/0"),listOf(rule(vpn)))}
    }
}
