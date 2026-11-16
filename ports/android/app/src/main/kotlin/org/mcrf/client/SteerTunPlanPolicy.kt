package org.mcrf.client

import org.json.JSONObject
import java.util.Collections

/** Exact module plan, bound to immutable user settings. Does not advertise an
 * engine capability or change settings; unknown/partial plans fail closed. */
internal object SteerTunPlanPolicy {
    const val IPV6_ADDRESS = "fdfe:dcba:9876::1"
    class Plan internal constructor(val ipv6: Boolean, val excludedApps: List<String>, val includedApps: List<String>)
    private val fields = setOf("schema", "mtu", "addresses", "routes", "dns", "excludedApps",
        "ipv6", "hostMustBeInsideVpn", "requiresVpnNetworkHandle")
    private fun strings(json: JSONObject, key: String, limit: Int): List<String> {
        val values = json.getJSONArray(key)
        require(values.length() <= limit) { "Слишком большой план Steer." }
        return (0 until values.length()).map { index ->
            val value = values.get(index)
            require(value is String && value.isNotEmpty()) { "Некорректная строка плана Steer." }
            value
        }
    }
    private fun validPackages(values: List<String>) = values.distinct().size == values.size && values.all {
        it.length <= 255 && it.matches(Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+"))
    }
    fun parse(plan: JSONObject, settings: JSONObject, hostPackage: String? = null): Plan {
        val scope = settings.opt("appMode") ?: "all"
        require(scope == "all" || scope == "include") { "Неизвестная область приложений Steer." }
        val include = scope == "include"
        require(plan.keys().asSequence().toSet() == fields + if (include) setOf("includedApps", "controlApp") else emptySet()) { "Неизвестные поля плана TUN Steer." }
        require(!settings.has("ipv6") || settings.get("ipv6") is Boolean) { "Некорректный режим IPv6 Steer." }
        val ipv6 = settings.optBoolean("ipv6", false)
        require(!ipv6 || (settings.optInt("version", 1) == 1 && settings.optInt("steerVersion", 1) == 2)) { "Для IPv6 нужен новый формат Steer." }
        require(plan.opt("schema") == if (ipv6) 2 else 1)
        require(plan.opt("mtu") == 1500 && plan.opt("hostMustBeInsideVpn") == true &&
            plan.opt("requiresVpnNetworkHandle") == true) { "Несовместимый план TUN Steer." }
        require(plan.opt("ipv6") == if (ipv6) "capture_v6_native" else "block_for_captured_apps")
        val addresses = if (ipv6) listOf("10.89.0.2/32", "$IPV6_ADDRESS/126") else listOf("10.89.0.2/32")
        val routes = if (ipv6) listOf("0.0.0.0/0", "::/0") else listOf("0.0.0.0/0")
        require(strings(plan, "addresses", 2) == addresses && strings(plan, "routes", 2) == routes &&
            strings(plan, "dns", 1) == listOf("10.89.0.1")) { "Неполный план адресов/маршрутов/DNS Steer." }
        val excluded = strings(plan, "excludedApps", 512)
        val originalExcluded = strings(settings, "excludedApps", 512)
        require(validPackages(excluded) && validPackages(originalExcluded)) { "Некорректные исключения приложений Steer." }
        val included: List<String>
        if (include) {
            require(settings.optInt("version", 1) == 1 && settings.optInt("steerVersion", 1) == 2)
            require(hostPackage != null && validPackages(listOf(hostPackage)) &&
                settings.opt("hostPackage") == hostPackage && plan.opt("controlApp") == hostPackage &&
                hostPackage !in originalExcluded) { "Модуль изменил управляющее приложение Steer." }
            val selected = strings(settings, "vpnApps", 256)
            require(selected.isNotEmpty() && validPackages(selected) && selected.none { it in originalExcluded }) { "Некорректная область выбранных приложений Steer." }
            included = strings(plan, "includedApps", 257)
            val expected = if (hostPackage in selected) selected else selected + hostPackage
            require(excluded.isEmpty() && included == expected && validPackages(included)) { "Модуль изменил выбранные приложения Steer." }
        } else {
            require(!settings.has("hostPackage"))
            require(excluded == originalExcluded) { "Модуль изменил исключения приложений Steer." }
            included = emptyList()
        }
        return Plan(ipv6, Collections.unmodifiableList(ArrayList(excluded)), Collections.unmodifiableList(ArrayList(included)))
    }
}
