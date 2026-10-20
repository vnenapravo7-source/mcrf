package org.mcrf.client

import org.json.JSONObject

/** Independently implemented from the qThrone settings behavior, without imported identifiers. */
data class SubscriptionOptions(
    val userAgent: String = DEFAULT_USER_AGENT,
    val intervalMinutes: Int = 360,
    val clearOnSuccess: Boolean = true,
    val sendDeviceHeaders: Boolean = false
) {
    fun json() = JSONObject().put("userAgent", userAgent).put("intervalMinutes", intervalMinutes)
        .put("clearOnSuccess", clearOnSuccess).put("sendDeviceHeaders", sendDeviceHeaders)
    fun headers(hwid: String?, model: String?, osVersion: String?): Map<String, String> {
        header(userAgent)
        val result = linkedMapOf("User-Agent" to userAgent)
        if (sendDeviceHeaders) {
            listOf("x-hwid" to hwid, "x-device-model" to model, "x-device-os" to "Android", "x-ver-os" to osVersion)
                .forEach { (key, value) -> if (!value.isNullOrBlank()) { header(value); result[key] = value } }
        }
        return result
    }
    companion object {
        const val SECTION = "subscriptionOptions"
        const val DEFAULT_USER_AGENT = "MCRF/0.1-port"
        fun header(value: String) {
            require(value.length in 1..512 && value.all { it.code in 32..126 }) {
                "Значение HTTP-заголовка: 1–512 печатных латинских символов, без переносов строк."
            }
        }
        fun from(value: JSONObject): SubscriptionOptions {
            require(value.keys().asSequence().all { it in setOf("userAgent", "intervalMinutes", "clearOnSuccess", "sendDeviceHeaders") }) { "Неизвестные настройки подписок." }
            val agent = if (value.has("userAgent")) value.get("userAgent").also { require(it is String) }.toString() else DEFAULT_USER_AGENT
            header(agent)
            val minutes = if (value.has("intervalMinutes")) {
                val number = value.get("intervalMinutes"); require(number is Int || number is Long)
                (number as Number).toLong().also { require(it in 0..Int.MAX_VALUE.toLong()) }.toInt()
            } else 360
            SubscriptionSchedule.validateMinutes(minutes)
            fun flag(key: String, default: Boolean): Boolean = if (!value.has(key)) default else value.get(key).also { require(it is Boolean) } as Boolean
            return SubscriptionOptions(agent, minutes, flag("clearOnSuccess", true), flag("sendDeviceHeaders", false))
        }
        /** Backup registry validator; old backups may omit the whole section. */
        @JvmStatic fun validate(value: JSONObject): JSONObject = from(value).json()
    }
}
