package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Queued with the entire P1 batch; no TUN/socket/device mutation. */
class SteerTunPlanPolicyTest {
    private fun settings(ipv6: Boolean) = JSONObject().put("version", 1).put("steerVersion", if (ipv6) 2 else 1)
        .put("ipv6", ipv6).put("excludedApps", JSONArray().put("org.example.excluded"))
    private fun plan(ipv6: Boolean) = JSONObject().put("schema", if (ipv6) 2 else 1).put("mtu", 1500)
        .put("addresses", JSONArray().put("10.89.0.2/32").also { if (ipv6) it.put("fdfe:dcba:9876::1/126") })
        .put("routes", JSONArray().put("0.0.0.0/0").also { if (ipv6) it.put("::/0") })
        .put("dns", JSONArray().put("10.89.0.1"))
        .put("excludedApps", JSONArray().put("org.example.excluded"))
        .put("ipv6", if (ipv6) "capture_v6_native" else "block_for_captured_apps")
        .put("hostMustBeInsideVpn", true).put("requiresVpnNetworkHandle", true)

    @Test fun exactLegacyAndDualStackPlansBindToSettingsWithoutMutation() {
        listOf(false, true).forEach { ipv6 ->
            val source = plan(ipv6); val input = settings(ipv6)
            val before = source.toString(); val settingsBefore = input.toString()
            val result = SteerTunPlanPolicy.parse(source, input)
            assertEquals(ipv6, result.ipv6)
            assertEquals(listOf("org.example.excluded"), result.excludedApps)
            assertEquals(before, source.toString()); assertEquals(settingsBefore, input.toString())
            assertThrows(UnsupportedOperationException::class.java) { (result.excludedApps as MutableList).clear() }
        }
    }
    @Test fun missingIpv6CaptureOrDowngradeNeverSilentlyPasses() {
        listOf(plan(false), plan(true).put("routes", JSONArray().put("0.0.0.0/0")),
            plan(true).put("addresses", JSONArray().put("10.89.0.2/32")),
            plan(true).put("ipv6", "block_for_captured_apps")).forEach { source ->
            assertThrows(IllegalArgumentException::class.java) { SteerTunPlanPolicy.parse(source, settings(true)) }
        }
        assertThrows(IllegalArgumentException::class.java) { SteerTunPlanPolicy.parse(plan(true), settings(false)) }
    }
    @Test fun unknownFieldsAndCoercedSecurityFlagsAreRejected() {
        listOf(plan(false).put("extra", true), plan(false).put("hostMustBeInsideVpn", "true"),
            plan(false).put("schema", "1"), plan(false).put("requiresVpnNetworkHandle", false)).forEach { source ->
            assertThrows(IllegalArgumentException::class.java) { SteerTunPlanPolicy.parse(source, settings(false)) }
        }
    }
    @Test fun moduleCannotAddRemoveOrDuplicateExcludedApps() {
        listOf(JSONArray(), JSONArray().put("org.example.other"),
            JSONArray().put("org.example.excluded").put("org.example.excluded")).forEach { excluded ->
            assertThrows(IllegalArgumentException::class.java) {
                SteerTunPlanPolicy.parse(plan(false).put("excludedApps", excluded), settings(false))
            }
        }
    }
    @Test fun newIpv6SettingNeedsExplicitBooleanAndNewVersion() {
        listOf(settings(true).put("steerVersion", 1), settings(true).put("version", 2), settings(true).put("ipv6", "true")).forEach { input ->
            assertThrows(IllegalArgumentException::class.java) { SteerTunPlanPolicy.parse(plan(true), input) }
        }
    }
    private fun includeSettings() = settings(false).put("steerVersion", 2).put("appMode", "include")
        .put("hostPackage", "org.example.host").put("vpnApps", JSONArray().put("org.example.selected"))
    private fun includePlan() = plan(false).put("excludedApps", JSONArray()).put("controlApp", "org.example.host")
        .put("includedApps", JSONArray().put("org.example.selected").put("org.example.host"))
    @Test fun includeOnlyPlanHasExactSelectedAppsPlusExplicitTrustedControlHost() {
        val input = includeSettings(); val source = includePlan()
        val before = input.toString(); val planBefore = source.toString()
        val result = SteerTunPlanPolicy.parse(source, input, "org.example.host")
        assertEquals(listOf("org.example.selected", "org.example.host"), result.includedApps)
        assertTrue(result.excludedApps.isEmpty()); assertEquals(before, input.toString()); assertEquals(planBefore, source.toString())
        assertThrows(UnsupportedOperationException::class.java) { (result.includedApps as MutableList).clear() }
        input.getJSONArray("vpnApps").put("org.example.host") // selected host must not be duplicated
        assertEquals(2, SteerTunPlanPolicy.parse(source, input, "org.example.host").includedApps.size)
    }
    @Test fun includeOnlyCannotDropAddOrSpoofHostAndUserScope() {
        listOf(includePlan().put("includedApps", JSONArray().put("org.example.selected")),
            includePlan().put("includedApps", JSONArray().put("org.example.selected").put("org.example.host").put("org.example.extra")),
            includePlan().put("includedApps", JSONArray().put("org.example.host").put("org.example.selected")),
            includePlan().put("controlApp", "org.example.other"),
            includePlan().put("excludedApps", JSONArray().put("org.example.excluded"))).forEach { source ->
            assertThrows(IllegalArgumentException::class.java) { SteerTunPlanPolicy.parse(source, includeSettings(), "org.example.host") }
        }
        assertThrows(IllegalArgumentException::class.java) { SteerTunPlanPolicy.parse(includePlan(), includeSettings()) }
        listOf(includeSettings().put("steerVersion", 1), includeSettings().put("hostPackage", "org.example.other"),
            includeSettings().put("vpnApps", JSONArray()), includeSettings().put("excludedApps", JSONArray().put("org.example.host"))).forEach { input ->
            assertThrows(IllegalArgumentException::class.java) { SteerTunPlanPolicy.parse(includePlan(), input, "org.example.host") }
        }
    }
}
