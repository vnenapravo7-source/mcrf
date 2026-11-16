package org.mcrf.client

import org.json.JSONObject

/** Local, opt-in, exact catalogue identity. Never disables the IPv6 TUN barrier. */
internal object ConnectionAddressFamily {
    const val PREFIX = "connection-ipv4-dns:"
    const val NOTE = "Совместимость IPv4 выбранного сервера: имена разрешаются в IPv4; IPv6-защита TUN и остальные подключения не изменены."
    fun key(id: String): String {
        require(id.matches(Regex("[a-f0-9]{64}"))) { "Некорректный идентификатор подключения." }
        return PREFIX + id
    }
    fun apply(source: JSONObject, enabled: Boolean): JSONObject =
        if (enabled) CarrierAddressFamily.ipv4Only(source) else source
}
