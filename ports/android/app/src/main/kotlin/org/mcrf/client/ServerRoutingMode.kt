package org.mcrf.client

/** Per-server local metadata, independent of the provider's immutable link. */
internal object ServerRoutingMode {
    const val KEY="routingRules"
    val choices=linkedMapOf(
        "mcrf" to "Только правила MCRF",
        "server" to "Только серверные правила",
        "mcrf_first" to "Все правила, приоритет MCRF",
        "server_first" to "Все правила, приоритет серверных"
    )
    fun valid(value:String)=value in choices
    fun initial(context:android.content.Context,entry:ServerImport.Server):String {
        entry.routingRules?.let {require(valid(it));return it}
        val legacy=entry.carrier?.let {raw->runCatching {CsqttGenerations.family(raw).engine!="csqtt25"}.getOrDefault(false)}?:false
        if(legacy)return "mcrf"
        val local=RoutingStore(context).apply {load()}.snapshot()
        return if(CsqttRoutingPrecedence.usesServer(context,local)&&entry.configuration?.let(CsqttRoutingPrecedence::hasSourceRules)!=true)"server"else"mcrf_first"
    }
    fun help(value:String)=when(value){
        "mcrf"->"Используются только локальные правила MCRF."
        "server"->"Используются только правила, переданные сервером."
        "mcrf_first"->"Сначала проверяются правила MCRF, затем серверные. Используется первое совпавшее правило."
        "server_first"->"Сначала проверяются серверные правила, затем правила MCRF. Используется первое совпавшее правило."
        else->"Сохраняется действующий режим этого сервера."
    }
    fun engineLocal(source:org.json.JSONObject,mode:String):org.json.JSONObject {
        if(mode=="mcrf")return org.json.JSONObject(source.toString())
        require(valid(mode)){"Неизвестный режим правил сервера."}
        if(mode=="server")return RouteRules.defaults().put("globalDns",source.optJSONObject("globalDns")?.let {org.json.JSONObject(it.toString())}?:DnsConfig.defaults())
        val result=org.json.JSONObject(source.toString())
        val scope=RoutingAppPolicies.systemScope(source)
        val rules=org.json.JSONArray()
        fun appRule(id:String,apps:Collection<String>,target:String,invert:Boolean=false){
            if(apps.isEmpty())return
            rules.put(org.json.JSONObject().put("id",id).put("name","Приложения MCRF").put("enabled",true)
                .put("text","").put("mode","apps").put("target",target)
                .put("nativeMatch",org.json.JSONObject().put("package_name",org.json.JSONArray(apps)).apply {if(invert)put("invert",true)}))
        }
        appRule("session-local-direct",scope.direct,"direct")
        if(source.optString("appMode","all")=="include")appRule("session-local-outside",scope.forced,"direct",true)
        appRule("session-local-forced",scope.forced,"proxy")
        val original=source.getJSONArray("rules")
        repeat(original.length()){i->val rule=original.getJSONObject(i)
            if(!RoutingDefaultsRussianAppsPolicy.systemRule(rule)&&rule.optString("id") !in scope.projected)rules.put(org.json.JSONObject(rule.toString()))
        }
        return result.put("rules",rules).put("excludedApps",org.json.JSONArray()).put("vpnApps",org.json.JSONArray()).put("appMode","all")
    }
    class Required:IllegalArgumentException("Выберите правила маршрутизации для каждого нового сервера CSQTT.")
}
