package org.mcrf.client

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal object InviteRussianApps {
    /** One-time onboarding action. Refresh must never re-add apps the user later removed. */
    fun apply(context:Context) {
        val installed=context.packageManager.getInstalledApplications(0).mapTo(hashSetOf()){it.packageName}
        val chosen=RoutingDefaultsRussianApps.installed(installed).mapTo(linkedSetOf()){it.packageName}
        if(chosen.isEmpty())return
        val store=RoutingStore(context).apply {load()}
        val state=store.snapshot()
        require(state.optString("appMode","all")!="include") {
            "Сначала явно смените область VPN «только выбранные приложения» в настройках маршрутов. Правило RU-app не расширяет её автоматически."
        }
        val rules=state.getJSONArray("rules")
        val id=RoutingDefaultsRussianAppsPolicy.INVITE_RULE_ID
        val existing=(0 until rules.length()).map {rules.getJSONObject(it)}.firstOrNull {it.optString("id")==id}
        require(existing==null||existing.optString("mode")=="apps"&&existing.optString("target")=="direct"&&!existing.has("nativeMatch")){"Идентификатор правила инвайта уже занят. Существующее правило сохранено."}
        val packages=chosen+(existing?.let {RouteRules.parse(it.getString("text")).getValue("package_name")}.orEmpty())
        require(RoutingDefaultsRussianAppsPolicy.conflicts(state,packages).isEmpty()) {
            "RU-app конфликтует с сохранёнными политиками VPN или правилами приложений. Разрешите конфликт вручную в настройках маршрутов."
        }
        val rule=JSONObject().put("id",id).put("name","RU-app · напрямую").put("mode","apps").put("target","direct").put("enabled",true)
            .put("text",packages.sorted().joinToString("\n"){"app:$it"})
        state.put("rules",JSONArray().put(rule).also {out->repeat(rules.length()){i->val old=rules.getJSONObject(i);if(old.optString("id")!=id)out.put(old)}})
        store.save(state)
    }
}
