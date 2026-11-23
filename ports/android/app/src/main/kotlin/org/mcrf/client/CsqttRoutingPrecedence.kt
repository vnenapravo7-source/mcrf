package org.mcrf.client

import org.json.JSONObject

/** Session policy selection only; never writes the user's routing store. */
internal object CsqttRoutingPrecedence {
    fun hasSourceRules(source:JSONObject):Boolean {
        val route=source.optJSONObject("route")?:return false
        val final=route.optString("final")
        val outbounds=source.optJSONArray("outbounds")
        if(outbounds!=null&&(0 until outbounds.length()).map(outbounds::getJSONObject).any {
            it.optString("tag")==final&&it.optString("type") in setOf("direct","block")
        })return true
        val rules=route.optJSONArray("rules")?:return false
        return (0 until rules.length()).map(rules::getJSONObject).any {rule->
            val loopback=rule.keys().asSequence().toSet()==setOf("ip_cidr","action","outbound")&&rule.optString("action")=="route"&&
                rule.optJSONArray("ip_cidr")?.toString()==org.json.JSONArray().put("127.0.0.0/8").put("::1/128").toString()&&
                rule.optString("outbound").matches(Regex("mcrf-loopback(?:-[1-9][0-9]*)?"))&&outbounds!=null&&
                (0 until outbounds.length()).map(outbounds::getJSONObject).any {out->
                    out.keys().asSequence().toSet()==setOf("type","tag","inet4_bind_address","inet6_bind_address")&&out.optString("type")=="direct"&&
                        out.optString("tag")==rule.optString("outbound")&&out.optString("inet4_bind_address")=="127.0.0.1"&&out.optString("inet6_bind_address")=="::1"
                }
            !loopback&&rule.optString("action") !in setOf("sniff","hijack-dns","resolve")
        }
    }
    fun usesServer(context:android.content.Context,local:JSONObject):Boolean {
        val rules=local.optJSONArray("rules")
        val needsCountry=rules!=null&&(0 until rules.length()).any {RouteRules.countryPreset(rules.getJSONObject(it))}
        val country=if(needsCountry)java.util.zip.GZIPInputStream(context.assets.open("russia-routes.bin")).use {
            String(ImportIO.read(it),Charsets.UTF_8)
        }else null
        return usesServer(local,country)
    }
    fun usesServer(local:JSONObject,baseCountryText:String?=null):Boolean {
        if(local.optString("defaultTarget","proxy")!="proxy"||local.optString("appMode","all")!="all")return false
        if(listOf("excludedApps","vpnApps").any { (local.optJSONArray(it)?.length()?:0)>0 })return false
        val rules=local.optJSONArray("rules")?:return true
        return (0 until rules.length()).map(rules::getJSONObject).filter {it.optBoolean("enabled",true)}.all {
            baseCountryRule(it)&&baseCountryText!=null&&it.optString("text")==baseCountryText
        }
    }

    private fun baseCountryRule(rule:JSONObject):Boolean {
        if(rule.keys().asSequence().any {it !in setOf("id","name","text","mode","target","invert","enabled","builtinPreset")})return false
        if(!RouteRules.countryPreset(rule)||rule.has("nativeMatch")||rule.has("dns")||
            rule.has("geoPreset")||(rule.optJSONArray("presetIds")?.length()?:0)>0)return false
        if(rule.optString("mode")!="addresses")return false
        return when(rule.optString("builtinPreset")) {
            RouteRules.COUNTRY_DIRECT->rule.optString("target")=="direct"&&!rule.optBoolean("invert",false)
            "outside-ru"->rule.optString("target")=="proxy"&&rule.optBoolean("invert",false)
            else->false
        }
    }
}
