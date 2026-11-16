package org.mcrf.client

import org.json.JSONObject

data class WarpSettings(val protocol: String = "auto", val country: String = "foreign", val excludeDme: Boolean = true, val extended: Boolean = false, val termsAccepted: Boolean = false) {
    fun options() = WarpOperations.Options(protocol, if(country=="any")"foreign" else country, true, extended)
    fun json(): JSONObject = JSONObject().put("protocol",protocol).put("country",country).put("excludeDme",excludeDme).put("extended",extended).put("termsAccepted",termsAccepted)
    companion object {
        const val SECTION = "warpOptions"
        const val TERMS_URL = "https://www.cloudflare.com/application/terms/"
        fun from(value: JSONObject): WarpSettings {
            require(value.keys().asSequence().all { it in setOf("protocol", "country", "excludeDme", "extended", "termsAccepted") }) { "Неизвестные настройки WARP." }
            fun text(key: String, default: String): String = if(value.has(key)) value.get(key).also { require(it is String) }.toString() else default
            fun flag(key: String, default: Boolean): Boolean = if(value.has(key)) value.get(key).also { require(it is Boolean) } as Boolean else default
            return WarpSettings(text("protocol","auto"),text("country","foreign"),flag("excludeDme",true),flag("extended",false),flag("termsAccepted",false)).also { it.options() }
        }
        fun validate(value: JSONObject): JSONObject = from(value).json()
    }
}
