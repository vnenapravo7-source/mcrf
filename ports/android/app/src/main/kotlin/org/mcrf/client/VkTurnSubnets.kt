package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject

/**
 * Fixed public TURN pools copied, in their original order, from CSQTT:
 * https://github.com/SanityProtocol/csqttr
 * Revision 0188606a243c92a54440be09dc59a93848c463aa
 * app/src/main/java/com/csqtt/client/TurnSubnetRegistry.kt
 * Selection semantics: SettingsStore.kt and ui/v2/dialogs/V2TurnSubnetsDialog.kt.
 * Changes: pure JSON helpers, strict validation and explicit-profile precedence;
 * no CIDR expansion, provider calls, probing or authentication changes.
 *
 * PolyForm Noncommercial License 1.0.0
 * https://polyformproject.org/licenses/noncommercial/1.0.0
 * Required Notice: Copyright 2026 amurcanov.
 * Required Notice: Commercial use of CSQTT requires a separate written license from the licensor.
 * Required Notice: Redistributions and derivative works must retain attribution to the original CSQTT project and these notices.
 */
internal object VkTurnSubnets {
    const val SELECTED = "selected_turn_subnet"
    const val IP_LIMIT = "turn_subnet_ip_limit"
    const val CUSTOM_IPS = "custom_turn_ips"
    const val UPSTREAM_REVISION = "0188606a243c92a54440be09dc59a93848c463aa"
    const val UPSTREAM_NOTICE = "CSQTT · https://github.com/SanityProtocol/csqttr\n" +
        "TurnSubnetRegistry.kt · $UPSTREAM_REVISION\n\n" +
        "PolyForm Noncommercial License 1.0.0\n" +
        "https://polyformproject.org/licenses/noncommercial/1.0.0\n\n" +
        "Required Notice: Copyright 2026 amurcanov.\n" +
        "Required Notice: Commercial use of CSQTT requires a separate written license from the licensor.\n" +
        "Required Notice: Redistributions and derivative works must retain attribution to the original CSQTT project and these notices."

    data class Selection(val subnetId: String = "", val ipLimit: String = "ALL")
    data class Pool(
        val id: String,
        val cidr: String,
        val ips: List<String>,
        val aliases: List<String> = emptyList(),
    )

    val ipLimits: List<String> = listOf("ALL", "1", "2", "4", "9")

    // Exact host lists, including upstream gaps and unusual host suffixes.
    // A CIDR is a display label, never a request to generate all its addresses.
    val pools: List<Pool> = listOf(
        Pool(
            id = "95_163_34_0_24",
            cidr = "95.163.34.0/24",
            ips = listOf("95.163.34.48", "95.163.34.128", "95.163.34.129", "95.163.34.130", "95.163.34.131", "95.163.34.132", "95.163.34.133", "95.163.34.134", "95.163.34.135", "95.163.34.136", "95.163.34.137", "95.163.34.138", "95.163.34.139", "95.163.34.140", "95.163.34.141", "95.163.34.142", "95.163.34.143", "95.163.34.144", "95.163.34.145", "95.163.34.146", "95.163.34.147", "95.163.34.148", "95.163.34.149", "95.163.34.150", "95.163.34.151", "95.163.34.152", "95.163.34.153", "95.163.34.154", "95.163.34.155", "95.163.34.156", "95.163.34.157", "95.163.34.158", "95.163.34.159", "95.163.34.160", "95.163.34.161", "95.163.34.162", "95.163.34.163", "95.163.34.164", "95.163.34.165", "95.163.34.166", "95.163.34.167", "95.163.34.168", "95.163.34.169", "95.163.34.170", "95.163.34.171", "95.163.34.172", "95.163.34.173", "95.163.34.174", "95.163.34.175", "95.163.34.176", "95.163.34.177", "95.163.34.178", "95.163.34.179", "95.163.34.180", "95.163.34.181", "95.163.34.182", "95.163.34.183", "95.163.34.184", "95.163.34.185", "95.163.34.186", "95.163.34.187", "95.163.34.188", "95.163.34.189", "95.163.34.190"),
        ),
        Pool(
            id = "91_231_135_0_24",
            cidr = "91.231.135.0/24",
            ips = listOf("91.231.135.85", "91.231.135.86", "91.231.135.87", "91.231.135.88", "91.231.135.89", "91.231.135.90", "91.231.135.91", "91.231.135.92", "91.231.135.93", "91.231.135.94", "91.231.135.95", "91.231.135.128", "91.231.135.129", "91.231.135.130", "91.231.135.131", "91.231.135.132", "91.231.135.133", "91.231.135.134", "91.231.135.135", "91.231.135.136", "91.231.135.137", "91.231.135.138", "91.231.135.139", "91.231.135.140", "91.231.135.141", "91.231.135.142", "91.231.135.143", "91.231.135.144", "91.231.135.145", "91.231.135.146", "91.231.135.147", "91.231.135.148", "91.231.135.149", "91.231.135.150", "91.231.135.151", "91.231.135.152", "91.231.135.153", "91.231.135.154", "91.231.135.155", "91.231.135.167", "91.231.135.168", "91.231.135.169", "91.231.135.170", "91.231.135.171", "91.231.135.172", "91.231.135.173", "91.231.135.174", "91.231.135.175", "91.231.135.176", "91.231.135.177", "91.231.135.178", "91.231.135.179", "91.231.135.180", "91.231.135.181", "91.231.135.182", "91.231.135.183", "91.231.135.184", "91.231.135.185", "91.231.135.186", "91.231.135.187", "91.231.135.188", "91.231.135.189", "91.231.135.190", "91.231.135.191"),
            aliases = listOf("91_231_132_0_22"),
        ),
        Pool(
            id = "90_156_232_0_21",
            cidr = "90.156.232.0/21",
            ips = listOf("90.156.234.253", "90.156.234.254", "90.156.234.255", "90.156.236.64", "90.156.236.65", "90.156.236.66", "90.156.236.67", "90.156.236.68", "90.156.236.69", "90.156.236.70", "90.156.236.71", "90.156.236.72", "90.156.236.73", "90.156.236.74", "90.156.236.75", "90.156.236.76", "90.156.236.77", "90.156.236.78", "90.156.236.79", "90.156.236.80", "90.156.236.81", "90.156.236.82", "90.156.236.83", "90.156.236.84", "90.156.236.85", "90.156.236.86", "90.156.236.87", "90.156.236.88", "90.156.236.89", "90.156.236.90", "90.156.236.91", "90.156.236.92", "90.156.236.93", "90.156.236.94", "90.156.236.95", "90.156.236.96", "90.156.236.97", "90.156.236.98", "90.156.236.99", "90.156.236.100", "90.156.236.101", "90.156.236.102", "90.156.236.103", "90.156.236.104", "90.156.236.105", "90.156.236.106", "90.156.236.107", "90.156.236.108", "90.156.236.114", "90.156.236.115", "90.156.236.117"),
            aliases = listOf("90_156_236_0_24"),
        ),
        // Upstream declares count=48 here, but its literal list contains 47 hosts.
        Pool(
            id = "193_203_43_0_24",
            cidr = "193.203.43.0/24",
            ips = listOf("193.203.43.0", "193.203.43.1", "193.203.43.2", "193.203.43.3", "193.203.43.4", "193.203.43.6", "193.203.43.7", "193.203.43.8", "193.203.43.9", "193.203.43.10", "193.203.43.11", "193.203.43.12", "193.203.43.13", "193.203.43.14", "193.203.43.15", "193.203.43.16", "193.203.43.17", "193.203.43.18", "193.203.43.19", "193.203.43.21", "193.203.43.22", "193.203.43.23", "193.203.43.24", "193.203.43.25", "193.203.43.26", "193.203.43.27", "193.203.43.28", "193.203.43.29", "193.203.43.30", "193.203.43.31", "193.203.43.32", "193.203.43.33", "193.203.43.34", "193.203.43.35", "193.203.43.36", "193.203.43.37", "193.203.43.38", "193.203.43.39", "193.203.43.40", "193.203.43.41", "193.203.43.42", "193.203.43.43", "193.203.43.44", "193.203.43.45", "193.203.43.46", "193.203.43.47", "193.203.43.48"),
            aliases = listOf("193_203_40_0_22"),
        ),
        Pool(
            id = "185_180_200_0_22",
            cidr = "185.180.200.0/22",
            ips = listOf("185.180.202.14", "185.180.202.15"),
        ),
    )

    fun pool(id: String): Pool? = pools.firstOrNull { it.id == id || id in it.aliases }

    /** Canonicalize known upstream aliases; unknown IDs/counts never become Automatic. */
    fun validate(selection: Selection): Selection {
        require(selection.ipLimit in ipLimits) {
            "Неизвестное количество IP TURN. Выберите Все, 1, 2, 4 или 9."
        }
        if (selection.subnetId.isEmpty()) return selection
        val selected = requireNotNull(pool(selection.subnetId)) {
            "Неизвестная подсеть TURN. Выберите подсеть из списка или Автоматически."
        }
        val limit = selection.ipLimit.toIntOrNull()
        require(limit == null || limit <= selected.ips.size) {
            "В подсети ${selected.cidr} только ${selected.ips.size} IP. Уменьшите количество."
        }
        return selection.copy(subnetId = selected.id)
    }

    /** Pass the vkSettings object itself, not the catalogue root. No storage I/O. */
    fun read(vkSettings: JSONObject): Selection {
        fun value(key: String, fallback: String): String {
            if (!vkSettings.has(key)) return fallback
            return requireNotNull(vkSettings.opt(key) as? String) {
                "Некорректная настройка TURN: $key. Выберите подсеть и количество заново."
            }
        }
        return validate(Selection(value(SELECTED, ""), value(IP_LIMIT, "ALL")))
    }

    fun ips(selection: Selection): List<String> {
        val valid = validate(selection)
        if (valid.subnetId.isEmpty()) return emptyList()
        val all = requireNotNull(pool(valid.subnetId)).ips
        return if (valid.ipLimit == "ALL") all.toList() else all.take(valid.ipLimit.toInt())
    }

    /** Deep copy; preserve account, source, manual calls and every unrelated field.
     * Generated addresses are a derived cache, never an authentication/source override.
     * The caller commits these fields inside its existing atomic persistence update.
     */
    fun write(vkSettings: JSONObject, selection: Selection): JSONObject {
        val valid = validate(selection)
        return JSONObject(vkSettings.toString())
            .put(SELECTED, valid.subnetId)
            .put(IP_LIMIT, valid.ipLimit)
            .put(CUSTOM_IPS, JSONArray(ips(valid)))
    }

    /** Apply at the effective-profile boundary, after generation resolution.
     * Only CSQTT 2.5 has a confirmed custom_turn_ips contract.
     * Returns a detached clone even when no change is needed. Explicit addresses win.
     * Cached vkSettings.custom_turn_ips is deliberately not trusted.
     */
    fun apply(profile: JSONObject, vkSettings: JSONObject): JSONObject {
        val copy = JSONObject(profile.toString())
        if (profile.optString("type") != "csqtt" || profile.opt("csqtt_generation") != "2.5") return copy
        if (hasExplicitIps(profile)) return copy
        val selected = read(vkSettings)
        val generated = ips(selected)
        if (generated.isNotEmpty()) copy.put(CUSTOM_IPS, JSONArray(generated))
        return copy
    }

    private fun hasExplicitIps(profile: JSONObject): Boolean {
        if (!profile.has(CUSTOM_IPS) || profile.isNull(CUSTOM_IPS)) return false
        return when (val value = profile.get(CUSTOM_IPS)) {
            is JSONArray -> value.length() > 0
            is String -> {
                require(value.isBlank()) { "Некорректный custom_turn_ips: ожидается список IP." }
                false
            }
            else -> throw IllegalArgumentException("Некорректный custom_turn_ips: ожидается список IP.")
        }
    }

    /** Both independently chosen values remain visible in the VkTurn entry row. */
    fun summary(selection: Selection): String {
        val valid = validate(selection)
        val subnet = if (valid.subnetId.isEmpty()) "Автоматически" else requireNotNull(pool(valid.subnetId)).cidr
        val count = if (valid.ipLimit == "ALL") "Все IP" else "${valid.ipLimit} IP"
        return "$subnet · $count"
    }
}
