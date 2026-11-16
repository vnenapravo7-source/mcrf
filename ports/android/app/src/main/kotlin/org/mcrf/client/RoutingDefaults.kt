package org.mcrf.client

import android.app.AlertDialog
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject

object RoutingDefaults {
    /** Public Android package identifiers; never infer packages from translated app names. */
    val russianPackages:List<String> get() = RoutingDefaultsRussianApps.items.map {it.packageName}
    fun applyAppMode(config: JSONObject, state: JSONObject, tun: Boolean, ruApps:Set<String> = emptySet()) {
        val mode = state.optString("appMode", "all")
        require(mode in setOf("all", "include")) { "Неизвестный режим приложений VPN." }
        if (mode != "include") return
        require(tun) { "Выбор приложений требует системного VPN." }
        val chosen=state.optJSONArray("vpnApps")?:JSONArray()
        val included=(0 until chosen.length()).map(chosen::getString).filterNot(ruApps::contains)
        require(included.isNotEmpty()) { "После исключений в списке VPN должно остаться хотя бы одно приложение." }
        val inbounds = config.getJSONArray("inbounds"); repeat(inbounds.length()) {
            val b = inbounds.getJSONObject(it); if (b.optString("type") == "tun") { b.remove("exclude_package"); b.put("include_package", JSONArray(included)) }
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
    /** Rule creation needs the complete bundled country list, not a domain-only SRS. */
    fun pickRule(activity: MainActivity, initial: Set<String>, chosen: (Set<String>, String?) -> Unit): AlertDialog = with(activity) {
        ThemedDialogBuilder(this).setTitle("Готовые пресеты")
            .setItems(arrayOf("Всё кроме РФ", "Списки сервисов")) { _, index ->
                if(index==0)run(MainActivity.Operation {
                    val country=java.util.zip.GZIPInputStream(assets.open("russia-routes.bin")).use {String(ImportIO.read(it),Charsets.UTF_8)}
                    runOnUiThread {if(!isFinishing&&!isDestroyed)chosen(emptySet(),country)}
                    ""
                },false) else pick(this,initial,false) {chosen(it,null)}
            }.setNegativeButton("Отмена",null).show()
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
