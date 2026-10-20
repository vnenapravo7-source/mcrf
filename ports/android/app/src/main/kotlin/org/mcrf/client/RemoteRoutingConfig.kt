package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.HttpURLConnection
import java.net.URL

object RemoteRoutingConfig {
    fun defaults() = JSONObject().put("mirror", "origin").put("mirrorUrl", "").put("adblock", false)
        .put("adblockUrl", "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-category-ads-all.srs")
        .put("autoRefresh", false).put("intervalMinutes", 360).put("profiles", JSONArray())
    fun value(state: JSONObject) = state.optJSONObject("remoteRouting") ?: defaults()
    fun https(raw: String): URI = URI(raw).also {
        require(it.scheme == "https" && !it.host.isNullOrBlank() && it.rawUserInfo == null && it.rawFragment == null) { "Нужен HTTPS-адрес без пароля и фрагмента." }
    }
    fun validate(s: JSONObject) {
        require(s.getString("mirror") in setOf("origin", "custom")) { "Неизвестное зеркало." }
        if (s.getString("mirror") == "custom") https(s.getString("mirrorUrl"))
        https(s.getString("adblockUrl")); require(s.getInt("intervalMinutes") in 30..10080) { "Интервал: от 30 минут до 7 дней." }
        val profiles = s.getJSONArray("profiles"); require(profiles.length() <= 32)
        val ids = hashSetOf<String>()
        repeat(profiles.length()) { val p = profiles.getJSONObject(it)
            require(p.getString("id").matches(Regex("[a-zA-Z0-9_-]{1,48}")) && ids.add(p.getString("id"))) { "Идентификатор профиля не уникален." }
            https(p.getString("url"))
        }
    }
    fun mirrored(url: String, s: JSONObject): String {
        https(url)
        if (s.getString("mirror") == "origin") return url
        return s.getString("mirrorUrl").trimEnd('/') + "/" + url
    }
    /** Redirects remain HTTPS; size/time bounded. Caller provides protected network when VPN is active. */
    fun fetch(url: String, max: Int = 4 * 1024 * 1024, open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }): ByteArray {
        var address = url
        repeat(6) {
            https(address)
            val c = open(URL(address)); c.connectTimeout = 15000; c.readTimeout = 30000; c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", "MCRF-Android/1")
            try {
                val code = c.responseCode
                if (code in setOf(301, 302, 303, 307, 308)) { address = URI(address).resolve(c.getHeaderField("Location") ?: error("Нет адреса перенаправления")).toString(); return@repeat }
                require(code in 200..299) { "Сервер ответил HTTP $code." }
                require(c.contentLengthLong <= max) { "Удалённый файл слишком большой." }
                return c.inputStream.use { input -> val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                    while (true) { val n = input.read(buffer); if (n < 0) break; require(output.size() + n <= max) { "Удалённый файл слишком большой." }; output.write(buffer, 0, n) }
                    output.toByteArray().also { require(it.isNotEmpty()) { "Удалённый файл пуст." } }
                }
            } finally { c.disconnect() }
        }
        error("Слишком много перенаправлений.")
    }
    fun due(p: JSONObject, s: JSONObject, now: Long): Boolean = s.optBoolean("autoRefresh") && p.optBoolean("enabled", true) && now - p.optLong("updatedAt") >= s.getLong("intervalMinutes") * 60000L
    /** Remote document: {version:1,rules:[ordinary MCRF lists]}. Never replaces local rules/default/Russia. */
    fun merge(state: JSONObject, id: String, document: JSONObject, now: Long): JSONObject {
        require(document.getInt("version") == 1) { "Неизвестный формат удалённых маршрутов." }
        val imported = document.getJSONArray("rules"); require(imported.length() <= 256)
        val next = JSONObject(state.toString()); val all = JSONArray(); val rules = next.getJSONArray("rules")
        repeat(rules.length()) { val r = rules.getJSONObject(it); if (r.optString("remoteOwner") != id) all.put(r) }
        repeat(imported.length()) { i ->
            val r = JSONObject(imported.getJSONObject(i).toString())
            require(!r.optBoolean("invert") && !r.has("builtinPreset")) { "Удалённый профиль не может менять исключение РФ." }
            r.put("id", "remote-$id-$i").put("remoteOwner", id)
            val validation = RouteRules.defaults(); validation.getJSONArray("rules").put(r); RoutingStore.validate(validation)
            all.put(r)
        }
        // Remote specific lists precede the country fallback.
        val result = JSONArray(); repeat(all.length()) { if (all.getJSONObject(it).optString("builtinPreset") != "outside-ru") result.put(all.getJSONObject(it)) }
        repeat(all.length()) { if (all.getJSONObject(it).optString("builtinPreset") == "outside-ru") result.put(all.getJSONObject(it)) }
        next.put("rules", result)
        value(next).getJSONArray("profiles").let { a -> repeat(a.length()) { if (a.getJSONObject(it).getString("id") == id) a.getJSONObject(it).put("updatedAt", now).put("error", "") } }
        RoutingStore.validate(next); return next
    }
    /** Retained binary/source API fails before network access or any mutation. */
    @Suppress("UNUSED_PARAMETER") fun refresh(store:RoutingStore,id:String,open:(URL)->HttpURLConnection={it.openConnection() as HttpURLConnection}):Int {
        error("Управление списками по ссылке удалено. Импортированные локальные правила сохранены.")
    }
    /** Retired URL manager must never inject downloadable/auto-updating remote SRS into a core. */
    @Suppress("UNUSED_PARAMETER") fun apply(config:JSONObject,state:JSONObject,proxy:String,direct:String)=Unit
}