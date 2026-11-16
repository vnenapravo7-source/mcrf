package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject

/** Preserve public sing-box matching conditions, including conjunctions and logical nesting. */
object RemoteRoutingImports {
    private val matchKeys=setOf("type","mode","rules","invert","domain","domain_suffix","domain_keyword","domain_regex","ip_cidr","source_ip_cidr","ip_is_private","source_ip_is_private","ip_version","network","protocol","inbound","port","port_range","source_port","source_port_range","process_name","process_path","process_path_regex","package_name","package_name_regex","network_type","network_is_expensive","network_is_constrained","wifi_ssid","wifi_bssid","rule_set")
    fun validateMatch(match:JSONObject,depth:Int=0) {
        require(depth<=16&&match.toString().length<=262144){"Слишком сложное правило маршрута."}
        require(match.keys().asSequence().all {it in matchKeys}){"Правило содержит неизвестное условие."}
        if(match.optString("type")=="logical") {
            require(match.optString("mode") in setOf("and","or"))
            require(match.keys().asSequence().all {it in setOf("type","mode","rules","invert")})
            val a=match.getJSONArray("rules");require(a.length() in 1..256);repeat(a.length()){validateMatch(a.getJSONObject(it),depth+1)}
        }else {
            require(!match.has("type")||match.getString("type")=="default");require(!match.has("rules")&&!match.has("mode"))
            require(match.keys().asSequence().any {it !in setOf("type","invert")}){"Пустое условие не может стать правилом для всего трафика."}
            match.keys().forEach {key ->
                val raw=match.get(key)
                require(raw is String||raw is Number||raw is Boolean||raw is JSONArray){"Некорректное условие $key."}
                val values=if(raw is JSONArray)raw else JSONArray().put(raw);require(values.length() in 1..100000)
                repeat(values.length()){i -> val v=values.get(i);require(v is String||v is Number||v is Boolean)
                    if(key=="package_name")RouteRules.validatePackage(v.toString())
                    if(key in setOf("ip_cidr","source_ip_cidr"))require(RouteRules.parse(v.toString()).getValue("ip_cidr").isNotEmpty())
                    if(key in setOf("domain","domain_suffix"))require(RouteRules.parse("DOMAIN,"+v.toString()).getValue("domain").isNotEmpty())
                    if(key in setOf("port","source_port"))require(v.toString().toIntOrNull() in 0..65535)
                }
            }
        }
        if(match.has("invert"))require(match.get("invert") is Boolean)
    }
    fun hasAppConditions(match:JSONObject):Boolean=match.keys().asSequence().any {it.startsWith("package_")||it.startsWith("process_")}||match.optJSONArray("rules")?.let {a -> (0 until a.length()).any {hasAppConditions(a.getJSONObject(it))}}==true
    fun recordCount(match:JSONObject):Int=match.keys().asSequence().sumOf {key ->
        val value=match.get(key)
        when {key=="rules"&&value is JSONArray->(0 until value.length()).sumOf {recordCount(value.getJSONObject(it))};key in setOf("type","mode","invert")->0;value is JSONArray->value.length();else->1}
    }
    fun dnsSafe(match:JSONObject):Boolean {
        val allowed=setOf("type","mode","rules","invert","domain","domain_suffix","domain_keyword","domain_regex","rule_set","network_type","network_is_expensive","network_is_constrained","wifi_ssid","wifi_bssid")
        return match.keys().asSequence().all {it in allowed}&&(match.optJSONArray("rules")?.let {a -> (0 until a.length()).all {dnsSafe(a.getJSONObject(it))}}?:true)
    }
    fun normalize(document: JSONObject): JSONObject {
        if(document.optString("kind")!="throne-route-profile")return document
        require(document.optInt("v",1)==1&&!document.optBoolean("raw")&&(document.optJSONArray("endpoints")?.length()?:0)==0){"Raw/endpoint-профили требуют отдельного движка."}
        val result=JSONArray();val input=document.getJSONArray("rules")
        repeat(input.length()){i -> val r=input.getJSONObject(i)
            require(r.keys().asSequence().all {it in matchKeys||it in setOf("name","outbound","action","reject_method","no_drop")}){"Правило содержит неизвестное поле Throne."}
            val token=r.optString("type","custom")
            require(token in setOf("custom","logical","default","simple_address_proxy","simple_address_bypass","simple_address_block","simple_process_name_proxy","simple_process_name_bypass","simple_process_name_block","simple_process_path_proxy","simple_process_path_bypass","simple_process_path_block")){"Неизвестный тип или выход WARP-bypass требует отдельной привязки."}
            val target=when {
                token.endsWith("_proxy")->"proxy";token.endsWith("_bypass")&&!token.contains("warp")->"direct";token.endsWith("_block")->"block"
                token=="custom"||token in setOf("logical","default")->r.optString("outbound","proxy")
                else->error("Тип правила Throne требует привязки дополнительного выхода.")
            }
            require(target in setOf("proxy","direct","block")){"Именованные выходы/WARP-bypass требуют явной привязки профиля."}
            require(r.optString("action",if(target=="block")"reject" else "route")==if(target=="block")"reject" else "route"){"Это действие маршрута требует отдельной интеграции."}
            val match=JSONObject();r.keys().forEach {key ->if(key in matchKeys&&key!="type")match.put(key,r.get(key))}
            if(token=="logical")match.put("type","logical");validateMatch(match)
            if(r.has("no_drop"))require(r.get("no_drop") is Boolean)
            if(r.has("reject_method"))require(r.getString("reject_method") in setOf("default","drop"))
            val rule=JSONObject().put("id","import-$i").put("name",r.optString("name","Маршрут ${i+1}").ifBlank {"Маршрут ${i+1}"}).put("text","").put("mode","addresses").put("target",target).put("enabled",true).put("nativeMatch",match)
            if(target=="block")for(key in listOf("reject_method","no_drop"))if(r.has(key))rule.put(key,r.get(key))
            result.put(rule)
        }
        return JSONObject().put("version",1).put("rules",result)
    }
}
