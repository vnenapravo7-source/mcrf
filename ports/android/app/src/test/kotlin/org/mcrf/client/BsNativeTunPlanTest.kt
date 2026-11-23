package org.mcrf.client

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BsNativeTunPlanTest {
    private fun fixture()=JSONObject(File("../transports/swg/routing/tests/fixtures/tun-projection.json").readText())
    private fun parse(source:JSONObject,native:JSONObject)=BsNativeTunPlan.parse(native,JSONArray().put(source),"org.mcrf.client")
    private fun captures(routes:List<String>,address:String)=routes.any {val route=ClientTunCidr.parse(it);val host=ClientTunCidr.parse("$address/${if(':' in address)128 else 32}");route.bits==host.bits&&route.number<=host.number&&route.end>=host.number}
    @Test fun exactSharedNativeProjectionPreservesDocumentAndImmutablePlan(){
        val fixture=fixture();val source=fixture.getJSONObject("source");val native=fixture.getJSONObject("projection");val before=source.toString();val nativeBefore=native.toString();val plan=parse(source,native)
        assertEquals(1400,plan.mtu);assertEquals(2,plan.addresses.size);assertEquals(listOf("198.19.255.53","fdfe:dcba:9876:dd54::53"),plan.dns)
        assertTrue(captures(plan.routes,"8.8.8.8"));assertTrue(captures(plan.routes,"2001:db8::1"));assertEquals(before,source.toString());assertEquals(nativeBefore,native.toString())
        assertThrows(UnsupportedOperationException::class.java){(plan.routes as MutableList).clear()}
    }
    @Test fun changedNativeAddressesRoutesScopeOrMtuAreRejectedBeforeOpeningTun(){
        listOf<(JSONObject)->Unit>({it.put("mtu",1500)},{it.put("addresses",JSONArray().put("10.89.0.2/32"))},{it.put("routes",JSONArray().put("0.0.0.0/0"))},{it.put("excludedRoutes",JSONArray().put("10.0.0.0/8"))},{it.put("includedApps",JSONArray().put("org.other"))},{it.put("extra",true)},{it.put("hostMustBeInsideVpn",false)}).forEach {change->val f=fixture();val native=f.getJSONObject("projection");change(native);assertThrows(IllegalArgumentException::class.java){parse(f.getJSONObject("source"),native)}}
    }
    @Test fun broadExclusionsKeepOnlyReservedDnsAliasesCaptured(){
        val f=fixture();val source=f.getJSONObject("source");val native=f.getJSONObject("projection");val excluded=JSONArray().put("198.18.0.0/15").put("fdfe::/16")
        source.put("route_exclude_address",excluded);native.put("excludedRoutes",JSONArray(excluded.toString()));val plan=parse(source,native)
        assertTrue(captures(plan.routes,"198.19.255.53"));assertTrue(captures(plan.routes,"fdfe:dcba:9876:dd54::53"));assertFalse(captures(plan.routes,"198.19.255.54"));assertFalse(captures(plan.routes,"fdfe::1"));assertTrue(captures(plan.routes,"8.8.8.8"))
    }
    @Test fun includeScopeAddsOnlyControlHostAndExcludeHostCannotDisableControl(){
        val f=fixture();val source=f.getJSONObject("source");val native=f.getJSONObject("projection");source.put("include_package",JSONArray().put("org.allowed"));native.put("includedApps",JSONArray().put("org.allowed"));assertEquals(listOf("org.allowed","org.mcrf.client"),parse(source,native).includedApps)
        source.remove("include_package");native.put("includedApps",JSONArray());source.put("exclude_package",JSONArray().put("org.mcrf.client"));native.put("excludedApps",JSONArray().put("org.mcrf.client"));assertThrows(IllegalArgumentException::class.java){parse(source,native)}
    }
    @Test fun ipv4PlanBlocksUncapturedIpv6AndPreservesHostAddress(){
        val f=fixture();val source=f.getJSONObject("source");val native=f.getJSONObject("projection");source.put("address",JSONArray().put("172.19.0.1/30")).put("mtu",576);native.put("addresses",JSONArray().put("172.19.0.1/30")).put("routes",JSONArray().put("0.0.0.0/0")).put("mtu",576)
        val plan=parse(source,native);assertEquals(listOf("172.19.0.1/30"),plan.addresses);assertEquals(listOf("198.19.255.53"),plan.dns);assertFalse(captures(plan.routes,"2001:db8::1"))
    }
    @Test fun unsupportedCaptureModesAndStringCoercionAreNotIgnored(){
        listOf("gso" to true,"auto_route" to false,"strict_route" to false,"mtu" to "1400","unknown" to false,"platform" to JSONObject().put("http_proxy",JSONObject().put("enabled",true))).forEach {(key,value)->val f=fixture();f.getJSONObject("source").put(key,value);assertThrows(IllegalArgumentException::class.java){parse(f.getJSONObject("source"),f.getJSONObject("projection"))}}
    }
}
