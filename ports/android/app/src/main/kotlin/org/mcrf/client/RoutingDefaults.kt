package org.mcrf.client

import android.app.AlertDialog
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject

object RoutingDefaults {
    /** Public Android package identifiers; never infer packages from translated app names. */
    val russianPackages:List<String> get() = RoutingDefaultsRussianApps.items.map {it.packageName}
    fun applyAppMode(config: JSONObject, state: JSONObject, tun: Boolean) {
        val mode = state.optString("appMode", "all")
        require(mode in setOf("all", "include")) { "Неизвестный режим приложений VPN." }
        if (mode != "include") return
        require(tun) { "Выбор приложений требует системного VPN." }
        val included = state.optJSONArray("vpnApps") ?: JSONArray(); require(included.length() > 0) { "Выбери хотя бы одно приложение для VPN." }
        val inbounds = config.getJSONArray("inbounds"); repeat(inbounds.length()) {
            val b = inbounds.getJSONObject(it); if (b.optString("type") == "tun") { b.remove("exclude_package"); b.put("include_package", JSONArray(included.toString())) }
        }
    }
    fun groups(items: List<PresetAssets.Item>): List<Pair<String, List<PresetAssets.Item>>> = items.groupBy { it.name }.entries
        .sortedWith(compareBy<Map.Entry<String, List<PresetAssets.Item>>> { when (it.key) { "Популярные сервисы 7" -> 0; "Все кроме РФ", "Всё кроме РФ" -> 1; else -> 2 } }.thenBy { it.key.lowercase() })
        .map { it.key to it.value }
    fun pick(activity: MainActivity, initial: Set<String>, includeCountry: Boolean = true, chosen: (Set<String>) -> Unit) = with(activity) {
        val groups = groups(PresetAssets.catalogue(this)).filter { includeCountry || it.second.none { item -> item.kind == "Исключение РФ" } }; val selection = initial.toMutableSet()
        val labels = groups.map { (name, entries) -> "$name · ${if (entries.any { it.domains > 0 } && entries.any { it.ips > 0 }) "домены и IP" else entries.first().kind}" }.toTypedArray()
        ThemedDialogBuilder(this).setTitle("Готовые пресеты · популярные сервисы")
            .setMultiChoiceItems(labels, groups.map { (_, entries) -> entries.all { it.id in selection } }.toBooleanArray()) { _, i, checked -> groups[i].second.forEach { if (checked) selection.add(it.id) else selection.remove(it.id) } }
            .setNegativeButton("Отмена", null).setPositiveButton("Выбрать") { _, _ -> chosen(selection) }.show()
    }
    fun addSelected(state: JSONObject, items: List<PresetAssets.Item>, ids: Set<String>, countryText: String? = null, target:String?=null) {
        require(target==null || target in setOf("proxy","direct","block"))
        require(ids.all {id->items.any {it.id==id}}){"Неизвестный готовый список."}
        groups(items.filter { it.id in ids }).forEach { (name, entries) ->
            if (name in setOf("Все кроме РФ", "Всё кроме РФ")) {
                // The existing country rule already has the verified direct-Russia semantics.
                val existing=(0 until state.getJSONArray("rules").length()).map {state.getJSONArray("rules").getJSONObject(it)}.firstOrNull {it.optString("builtinPreset")=="outside-ru"}
                val rule=existing?:RouteRules.outsideRussia(countryText ?: error("Нужен встроенный список РФ.")).also {state.getJSONArray("rules").put(it)}
                rule.put("enabled",true);if(target!=null)rule.put("target",target)
                if(target==null)state.put("defaultTarget","direct")
            } else {
                val wanted=entries.map {it.id}.toSet();val rules=state.getJSONArray("rules")
                val existing=(0 until rules.length()).map {rules.getJSONObject(it)}.firstOrNull {it.optString("name")==name&&PresetRules.ids(it).toSet()==wanted}
                if(existing!=null)existing.put("target",target?:"proxy").put("enabled",true)
                else RouteRules.insert(state, JSONObject().put("id", java.util.UUID.randomUUID().toString().replace("-", "")).put("name", name).put("text", "")
                    .put("mode", "addresses").put("target", target?:"proxy").put("enabled", true).put("presetIds", JSONArray(entries.map { it.id })))
            }
        }
    }
}
