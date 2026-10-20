package org.mcrf.client

import org.json.JSONObject

/** Defaults are inert until the required same-signer optional APK is installed. */
data class OptionalCoreSettings(
    val xrayVlessDefault: Boolean = false, val xrayMuxDefault: Boolean = false,
    val xrayMuxConcurrency: Int = 8, val customFragmentDefault: Boolean = false,
    val fragmentSize: String = "10-100", val fragmentSleep: String = "2-5",
    val mixedCaseSniDefault: Boolean = false
) {
    fun validate(): OptionalCoreSettings {
        require(xrayMuxConcurrency in 1..1024) { "Xray MUX: 1–1024 потока." }
        range(fragmentSize,1,16384); range(fragmentSleep,0,100)
        return this
    }
    fun json(): JSONObject = JSONObject().put("xrayVlessDefault",xrayVlessDefault).put("xrayMuxDefault",xrayMuxDefault)
        .put("xrayMuxConcurrency",xrayMuxConcurrency).put("customFragmentDefault",customFragmentDefault)
        .put("fragmentSize",fragmentSize).put("fragmentSleep",fragmentSleep).put("mixedCaseSniDefault",mixedCaseSniDefault)
    companion object {
        const val KEY = "optionalCores"
        fun range(text: String,min: Int,max: Int) {
            require(text.matches(Regex("[0-9]+(?:-[0-9]+)?"))) { "Диапазон: число или минимум-максимум." }
            val parts=text.split('-').map { it.toLongOrNull() }; val lo=parts.first(); val hi=parts.last()
            require(lo!=null && hi!=null && lo in min.toLong()..max.toLong() && hi in lo..max.toLong()) { "Диапазон должен быть внутри $min–$max." }
        }
        fun fromJson(j: JSONObject): OptionalCoreSettings {
            val defaults=OptionalCoreSettings().json()
            j.keys().forEach { key -> require(defaults.has(key)) { "Неизвестная настройка optional core." }; val value=j.get(key)
                require(when(defaults.get(key)) { is Boolean -> value is Boolean; is Int -> value is Int; is String -> value is String; else -> false }) { "Неверный тип настройки optional core." } }
            return OptionalCoreSettings(j.optBoolean("xrayVlessDefault"),j.optBoolean("xrayMuxDefault"),j.optInt("xrayMuxConcurrency",8),
                j.optBoolean("customFragmentDefault"),j.optString("fragmentSize","10-100"),j.optString("fragmentSleep","2-5"),j.optBoolean("mixedCaseSniDefault")).validate()
        }
    }
}
