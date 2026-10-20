package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject

/** Small portable references; the large immutable lists live in compiled assets. */
object PresetRules {
    fun ids(rule: JSONObject): List<String> {
        val values = rule.optJSONArray("presetIds") ?: return emptyList()
        require(values.length() in 1..68) { "Выбери от одного до 68 готовых списков." }
        val ids = (0 until values.length()).map { values.getString(it) }
        require(ids.distinct().size == ids.size && ids.all { it.matches(Regex("[0-9a-f]{16}")) }) { "Неизвестная ссылка на готовый список." }
        require(rule.optString("mode", "addresses") == "addresses") { "Готовые списки используются с условием «Домены/IP»." }
        return ids
    }
    fun needed(settings: JSONObject): Set<String> = settings.getJSONArray("rules").let { rules ->
        (0 until rules.length()).map { rules.getJSONObject(it) }.filter { it.optBoolean("enabled", true) }.flatMap(::ids).toSet()
    }
    fun tag(id: String) = "mcrf-preset-$id"
    fun match(ids: List<String>) = JSONObject().put("rule_set", JSONArray(ids.map(::tag)))
    fun attach(config: JSONObject, settings: JSONObject, paths: Map<String, String>) {
        val needed = needed(settings); if (needed.isEmpty()) return
        val route = config.getJSONObject("route")
        val values = route.optJSONArray("rule_set") ?: JSONArray().also { route.put("rule_set", it) }
        val tags = (0 until values.length()).map { values.getJSONObject(it).getString("tag") }.toSet()
        needed.forEach { id ->
            require(tag(id) !in tags) { "Импортированный профиль уже использует имя готового списка." }
            val path = paths[id] ?: throw IllegalArgumentException("Готовый список недоступен: обнови MCRF.")
            values.put(JSONObject().put("type","local").put("tag",tag(id)).put("format","binary").put("path",path))
        }
    }
}
