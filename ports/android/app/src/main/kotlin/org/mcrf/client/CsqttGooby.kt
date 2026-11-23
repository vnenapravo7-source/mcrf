package org.mcrf.client
import org.json.JSONObject
/** Optional built-in Gooby; omission preserves the old profile/runtime contract. */
internal object CsqttGooby {
    const val CAPABILITY="org.mcrf.engines.csqtt_gooby_builtin"
    val strategies=listOf("current","gentle","extended")
    fun enabled(profile:JSONObject):Boolean {
        if(!profile.has("gooby_enabled"))return false
        val value=profile.get("gooby_enabled");require(value is Boolean){"Некорректный переключатель Gooby."};return value
    }
    fun strategy(profile:JSONObject):String {
        if(!profile.has("gooby_strategy"))return "current"
        val value=profile.get("gooby_strategy");require(value is String&&value in strategies){"Некорректная стратегия Gooby."};return value
    }
    fun active(profile:JSONObject)=Csqtt25Profile.isProfile(profile)&&enabled(profile)&&profile.optString("turn_transport","udp")=="tcp"
    fun validate(profile:JSONObject){
        enabled(profile);strategy(profile)
        require(!active(profile)||!CsqttGenerations.automatic(profile)){"Для Gooby выбери CSQTT 2.5 без автоопределения поколений."}
    }
    fun copy(source:JSONObject,target:JSONObject){validate(source);for(key in listOf("gooby_enabled","gooby_strategy"))if(source.has(key))target.put(key,source.get(key))}
    fun arguments(profile:JSONObject):List<String>{
        validate(profile);if(!active(profile))return emptyList()
        return listOf("--gooby")+if(strategy(profile)=="current")emptyList()else listOf("--gooby-strategy",strategy(profile))
    }
    fun runtime(profile:JSONObject){
        validate(profile)
        if(!active(profile)){profile.remove("gooby_enabled");profile.remove("gooby_strategy")}
        else if(strategy(profile)=="current")profile.remove("gooby_strategy")
    }
}
