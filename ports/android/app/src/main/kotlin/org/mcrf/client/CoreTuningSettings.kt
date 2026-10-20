package org.mcrf.client

import org.json.JSONObject

data class CoreTuningSettings(
    val certificateStore: String = "system", val insecure: Boolean = false,
    val ntpEnabled: Boolean = false, val ntpServer: String = "time.cloudflare.com", val ntpPort: Int = 123, val ntpInterval: String = "30m",
    val clashEnabled: Boolean = false, val clashPort: Int = 9090, val clashSecret: String = "",
    val apiEnabled: Boolean = false, val apiPort: Int = 9091, val apiSecret: String = "",
    val muxDefault: Boolean = false, val muxProtocol: String = "smux", val muxConcurrency: Int = 8, val muxPadding: Boolean = false,
    val fragmentDefault: Boolean = false, val fragmentDelay: String = "500ms", val fingerprint: String = "",
    val idleTimeout: String = "", val keepAlive: String = "", val streamWindow: String = "", val connectionWindow: String = "",
    val maxStreams: Int = 0, val initialPacketSize: Int = 0, val disablePmtu: Boolean = false
) {
    fun validate(): CoreTuningSettings {
        require(certificateStore in setOf("system","mozilla")) { "Хранилище сертификатов: system/mozilla." }
        require(ntpServer.length in 1..253 && ntpServer.matches(Regex("[a-zA-Z0-9.:-]+"))) { "Некорректный NTP сервер." }
        require(ntpPort in 1..65535 && clashPort in 1024..65535 && apiPort in 1024..65535) { "Некорректный порт API/NTP." }
        require(!clashEnabled || !apiEnabled || clashPort != apiPort) { "Порты API должны отличаться." }
        listOf(clashSecret,apiSecret).forEach { require(it.length <= 255 && it.none(Char::isISOControl)) { "Секрет API не должен содержать управляющие символы." } }
        require(!clashEnabled || clashSecret.length >= 16) { "Clash API требует секрет от 16 символов." }
        require(!apiEnabled || apiSecret.length >= 16) { "sing-box API требует секрет от 16 символов." }
        require(muxProtocol in setOf("smux","yamux","h2mux") && muxConcurrency in 1..1024) { "Multiplex: smux/yamux/h2mux, concurrency 1–1024." }
        listOf(ntpInterval,fragmentDelay).forEach { duration(it,false) }
        listOf(idleTimeout,keepAlive).forEach { duration(it,true) }
        listOf(streamWindow,connectionWindow).forEach { require(it.isEmpty() || it.toLongOrNull()?.let { n -> n in 1..2147483647L } == true) { "Окно приёма: 1–2147483647 байт или пусто." } }
        require(maxStreams in 0..1000000 && (initialPacketSize == 0 || initialPacketSize in 1200..65527)) { "Streams: 0–1000000; QUIC packet: 0 или 1200–65527." }
        require(fingerprint in FINGERPRINTS) { "Неизвестный uTLS fingerprint." }
        return this
    }
    fun json(): JSONObject = JSONObject().put("certificateStore",certificateStore).put("insecure",insecure).put("ntpEnabled",ntpEnabled).put("ntpServer",ntpServer).put("ntpPort",ntpPort).put("ntpInterval",ntpInterval)
        .put("clashEnabled",clashEnabled).put("clashPort",clashPort).put("clashSecret",clashSecret).put("apiEnabled",apiEnabled).put("apiPort",apiPort).put("apiSecret",apiSecret)
        .put("muxDefault",muxDefault).put("muxProtocol",muxProtocol).put("muxConcurrency",muxConcurrency).put("muxPadding",muxPadding)
        .put("fragmentDefault",fragmentDefault).put("fragmentDelay",fragmentDelay).put("fingerprint",fingerprint).put("idleTimeout",idleTimeout).put("keepAlive",keepAlive)
        .put("streamWindow",streamWindow).put("connectionWindow",connectionWindow).put("maxStreams",maxStreams).put("initialPacketSize",initialPacketSize).put("disablePmtu",disablePmtu)
    companion object {
        const val KEY = "coreTuning"
        val FINGERPRINTS = setOf("","chrome","firefox","edge","safari","360","qq","ios","android","random","randomized","chrome_psk","chrome_psk_shuffle","chrome_padding_psk_shuffle","chrome_pq","chrome_pq_psk")
        fun duration(value: String, optional: Boolean) {
            if (optional && value.isEmpty()) return
            val match = Regex("([0-9]+(?:\\.[0-9]+)?)(ms|s|m|h)").matchEntire(value)
            val count = match?.groupValues?.get(1)?.toDoubleOrNull()
            val seconds = count?.times(when(match?.groupValues?.get(2)) { "ms" -> .001; "m" -> 60.0; "h" -> 3600.0; else -> 1.0 })
            require(seconds != null && seconds > 0 && seconds <= 31536000) { "Длительность: положительное число с ms/s/m/h, до 365 дней." }
        }
        fun fromJson(j: JSONObject): CoreTuningSettings { CoreTuningJson.validateTypes(j,CoreTuningSettings().json()); return CoreTuningSettings(j.optString("certificateStore","system"),j.optBoolean("insecure"),j.optBoolean("ntpEnabled"),j.optString("ntpServer","time.cloudflare.com"),j.optInt("ntpPort",123),j.optString("ntpInterval","30m"),j.optBoolean("clashEnabled"),j.optInt("clashPort",9090),j.optString("clashSecret"),j.optBoolean("apiEnabled"),j.optInt("apiPort",9091),j.optString("apiSecret"),j.optBoolean("muxDefault"),j.optString("muxProtocol","smux"),j.optInt("muxConcurrency",8),j.optBoolean("muxPadding"),j.optBoolean("fragmentDefault"),j.optString("fragmentDelay","500ms"),j.optString("fingerprint"),j.optString("idleTimeout"),j.optString("keepAlive"),j.optString("streamWindow"),j.optString("connectionWindow"),j.optInt("maxStreams"),j.optInt("initialPacketSize"),j.optBoolean("disablePmtu")).validate() }
    }
}

internal object CoreTuningJson {
    fun validateTypes(value: JSONObject, defaults: JSONObject) {
        value.keys().forEach { key ->
            require(defaults.has(key)) { "Неизвестная настройка: $key" }
            val item = value.get(key)
            require(when(defaults.get(key)) { is Boolean -> item is Boolean; is Int -> item is Int; is String -> item is String; else -> false }) { "Неверный тип настройки: $key" }
        }
    }
}
