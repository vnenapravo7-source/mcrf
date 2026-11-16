package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject

/** These categories were checked against actual upstream DAT; selection only adds lazy references. */
object GeoDataPresets {
    val targets=arrayOf("proxy","direct","block")
    val targetLabels=arrayOf("VPN / прокси","Напрямую","Блокировать")
    data class Item(val kind:String,val code:String,val name:String,val target:String)
    // Category identifiers checked against upstream domain-list-community's full git tree.
    val items=listOf(Item("geoip","ru","Россия · IP-сети","direct"), Item("geosite","ru","Россия · домены","direct")) + listOf(
        "google" to "Google", "yandex" to "Яндекс", "category-ads-all" to "Реклама",
        "youtube" to "YouTube", "telegram" to "Telegram", "whatsapp" to "WhatsApp",
        "discord" to "Discord", "facebook" to "Facebook", "instagram" to "Instagram",
        "twitter" to "X / Twitter", "tiktok" to "TikTok", "netflix" to "Netflix",
        "spotify" to "Spotify", "apple" to "Apple", "microsoft" to "Microsoft",
        "amazon" to "Amazon", "github" to "GitHub", "gitlab" to "GitLab",
        "cloudflare" to "Cloudflare", "dropbox" to "Dropbox", "onedrive" to "OneDrive",
        "zoom" to "Zoom", "steam" to "Steam", "epicgames" to "Epic Games",
        "ea" to "Electronic Arts", "playstation" to "PlayStation", "xbox" to "Xbox",
        "nintendo" to "Nintendo", "twitch" to "Twitch", "reddit" to "Reddit",
        "openai" to "OpenAI / ChatGPT", "anthropic" to "Anthropic / Claude",
        "google-gemini" to "Google Gemini", "vk" to "ВКонтакте", "mailru" to "Mail.ru",
        "ok" to "Одноклассники", "ozon" to "Ozon", "wildberries" to "Wildberries",
        "alibaba" to "Alibaba", "baidu" to "Baidu", "tencent" to "Tencent",
        "bilibili" to "Bilibili", "adobe" to "Adobe", "mozilla" to "Mozilla",
        "samsung" to "Samsung", "oracle" to "Oracle", "intel" to "Intel",
        "nvidia" to "NVIDIA", "amd" to "AMD", "ubuntu" to "Ubuntu",
        "debian" to "Debian", "jetbrains" to "JetBrains"
    ).map {(code,name)->Item("geosite",code,name,if(code=="category-ads-all")"block" else "proxy")}

    /** Explicit visible rules own these selectors, including when disabled or deleted. */
    fun addRules(state:JSONObject,chosen:List<Item>,target:String) {
        require(target in targets)
        if(chosen.isEmpty())return
        val geo=JSONObject(GeoDataConfig.value(state).toString())
        val next=JSONObject(state.toString())
        val selectors=geo.getJSONArray("selectors")
        chosen.distinctBy {it.kind+":"+it.code}.forEach {item ->
            require(item.kind=="geosite" || item.kind=="geoip"&&item.code=="ru")
            val code=GeoDataConfig.categoryCode(geo,item.kind,item.code)
            var index=(0 until selectors.length()).firstOrNull {i->val r=selectors.getJSONObject(i);r.getString("kind")==item.kind&&GeoDataConfig.categoryCode(geo,item.kind,r.getString("code")).equals(code,true)&&!r.optBoolean("bound")}
            if(index==null){index=selectors.length();selectors.put(JSONObject().put("kind",item.kind).put("code",code).put("target",target))}
            selectors.getJSONObject(index).put("code",code).put("managedRule",true).put("target",target)
            val identity="${item.kind}:${item.code}"
            val rules=next.getJSONArray("rules")
            val existing=(0 until rules.length()).map {rules.getJSONObject(it)}.firstOrNull {it.optString("geoPreset")==identity}
            if(existing!=null)existing.put("target",target).put("enabled",true)
            else RouteRules.insert(next,JSONObject().put("id",java.util.UUID.randomUUID().toString().replace("-",""))
                .put("name","${item.name} · ${item.kind}").put("text","").put("mode","addresses")
                .put("target",target).put("enabled",true).put("geoPreset",identity)
                .put("nativeMatch",JSONObject().put("rule_set",JSONArray().put("mcrf-geo-$index"))))
        }
        GeoDataConfig.validate(geo);state.put("geoData",geo).put("rules",next.getJSONArray("rules"))
    }
    fun selectors(state:JSONObject,desired:JSONArray):JSONObject {
        val next=JSONObject(state.toString());val geo=JSONObject(GeoDataConfig.value(next).toString())
        require(!geo.has("providerRules")){"Геокатегории связаны с импортированным профилем. Измени их повторным импортом профиля."}
        val selected=JSONArray(desired.toString())
        repeat(selected.length()){i->val r=selected.getJSONObject(i);r.put("code",GeoDataConfig.categoryCode(geo,r.getString("kind"),r.getString("code")))}
        val identities=(0 until selected.length()).map {selected.getJSONObject(it).let {r->r.getString("kind")+":"+r.getString("code").lowercase(java.util.Locale.ROOT)}}
        require(identities.distinct().size==identities.size){"Геокатегория выбрана несколько раз."}
        geo.put("selectors",selected);GeoDataConfig.validate(geo);next.put("geoData",geo);return next
    }
}
