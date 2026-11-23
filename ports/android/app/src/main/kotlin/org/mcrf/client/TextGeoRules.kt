package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject

/** Text remains editable; runtime uses the existing managed Geo selector contract. */
internal object TextGeoRules {
    private const val SOURCE = "textGeoSource"
    fun editable(rule: JSONObject): JSONObject {
        val result = JSONObject(rule.toString())
        val source = result.optJSONObject(SOURCE) ?: return result
        result.remove(SOURCE); result.remove("nativeMatch")
        for (key in listOf("text", "mode", "invert", "presetIds")) {
            result.remove(key)
            if (source.has(key)) result.put(key, source.get(key))
        }
        return result
    }
    /** Read the common string/missing case without cloning unrelated nested data.
     * Missing source text removes top-level text, just as editable() does.
     * Unusual JSON values retain its exact serialization/coercion semantics.
     */
    fun editableText(rule: JSONObject): String {
        if (rule.javaClass != JSONObject::class.java) return editable(rule).optString("text")
        val source = rule.opt(SOURCE)
        if (source != null && source !== JSONObject.NULL && source !is JSONObject)
            return editable(rule).optString("text")
        if (source is JSONObject && source.javaClass != JSONObject::class.java)
            return editable(rule).optString("text")
        val owner = source as? JSONObject ?: rule
        if (!owner.has("text")) return ""
        val text = owner.opt("text")
        return if (text is String) text else editable(rule).optString("text")
    }
    fun hasGeo(rule: JSONObject): Boolean {
        return hasGeoText(rule.optString("text"))
    }
    private fun hasGeoText(text: String): Boolean {
        val groups = RouteRules.parse(text)
        return groups.getValue("geoip").isNotEmpty() || groups.getValue("geosite").isNotEmpty()
    }
    fun single(rule: JSONObject): JSONObject {
        val state = RouteRules.defaults().put("rules", JSONArray().put(JSONObject(rule.toString())))
        compile(state)
        return state.getJSONArray("rules").getJSONObject(0)
    }
    fun compile(state: JSONObject) {
        val rules = state.getJSONArray("rules")
        repeat(rules.length()) { index ->
            val original = rules.getJSONObject(index)
            if (!hasGeoText(editableText(original))) return@repeat
            val draft = editable(original)
            require(!draft.has("nativeMatch")) { "Геокатегории нельзя смешивать с точным импортированным условием." }
            require(!draft.has("dns")) { "Для GeoIP/GeoSite используй общий DNS; отдельный DNS этого списка не поддерживается." }
            val geo = GeoDataConfig.value(state)
            val selectors = geo.getJSONArray("selectors")
            val groups = RouteRules.parse(draft.getString("text"))
            val tags = JSONArray()
            for (kind in listOf("geoip", "geosite")) for (raw in groups.getValue(kind)) {
                val code = GeoDataConfig.categoryCode(geo, kind, raw)
                var slot = (0 until selectors.length()).firstOrNull { i -> selectors.getJSONObject(i).let {
                    it.optString("kind") == kind && it.optString("code").equals(code, true) && it.optBoolean("managedRule")
                } }
                if (slot == null) {
                    require(selectors.length() < 64) { "Допускается до 64 геокатегорий в группе." }
                    slot = selectors.length()
                    selectors.put(JSONObject().put("kind", kind).put("code", code).put("target", "proxy").put("managedRule", true))
                }
                tags.put("mcrf-geo-$slot")
            }
            val matches = RouteRules.matchesWithGeo(draft, JSONObject().put("rule_set", tags))
            val match = if (matches.length() == 1) matches.getJSONObject(0) else JSONObject()
                .put("type", "logical").put("mode", "or").put("rules", matches)
            val source = JSONObject()
            for (key in listOf("text", "mode", "invert", "presetIds")) if (draft.has(key)) source.put(key, draft.get(key))
            draft.put(SOURCE, source).put("text", "").put("mode","addresses").put("nativeMatch", match)
            draft.remove("invert"); draft.remove("presetIds")
            rules.put(index, draft)
            state.put("geoData", geo)
        }
        state.optJSONObject(RoutingGroups.KEY)?.let { banks ->
            val groups = banks.getJSONArray("groups")
            repeat(groups.length()) { i ->
                val group = groups.getJSONObject(i)
                if (group.getString("id") != banks.getString("active")) compile(group.getJSONObject("data"))
            }
        }
    }
    fun stripSources(state:JSONObject) {
        val rules=state.getJSONArray("rules")
        repeat(rules.length()){rules.getJSONObject(it).remove(SOURCE)}
        state.optJSONObject(RoutingGroups.KEY)?.let {banks->val groups=banks.getJSONArray("groups")
            repeat(groups.length()){i->val group=groups.getJSONObject(i);if(group.getString("id")!=banks.getString("active"))stripSources(group.getJSONObject("data"))}}
    }
    /** Recover editor metadata only for an unchanged compiled condition, never an imported replacement. */
    fun recoverSources(state:JSONObject,reference:JSONObject) {
        val aligned=JSONObject(reference.toString())
        if(state.has(RoutingGroups.KEY)&&aligned.has(RoutingGroups.KEY)&&RoutingGroups.active(state)!=RoutingGroups.active(aligned))RoutingGroups.select(aligned,RoutingGroups.active(state))
        val previous=aligned.getJSONArray("rules")
        val byId=(0 until previous.length()).map {previous.getJSONObject(it)}.associateBy {it.getString("id")}
        val rules=state.getJSONArray("rules")
        repeat(rules.length()){i->val rule=rules.getJSONObject(i);val old=byId[rule.optString("id")]
            if(!rule.has(SOURCE)&&rule.has("nativeMatch")&&old?.has(SOURCE)==true&&
                ChangeHistoryStore.equivalent(rule.getJSONObject("nativeMatch").toString(),old.getJSONObject("nativeMatch").toString()))rule.put(SOURCE,old.getJSONObject(SOURCE))}
        val oldGroups=aligned.optJSONObject(RoutingGroups.KEY)?.optJSONArray("groups")?:return
        state.optJSONObject(RoutingGroups.KEY)?.let {banks->val groups=banks.getJSONArray("groups")
            repeat(groups.length()){i->val group=groups.getJSONObject(i)
                if(group.getString("id")!=banks.getString("active")){
                    val old=(0 until oldGroups.length()).map {oldGroups.getJSONObject(it)}.firstOrNull {it.getString("id")==group.getString("id")}
                    if(old?.getJSONObject("data")?.has("rules")==true)recoverSources(group.getJSONObject("data"),old.getJSONObject("data"))
                }}}
    }
}
