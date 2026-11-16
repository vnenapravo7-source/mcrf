package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject

/** One dispatcher/TUN, multiple explicitly selected saved SERVER profiles.
 * No per-rule engine choice, automatic fallback, preference or catalogue write. */
internal class RouteServerPlan(settings:JSONObject,catalogue:List<ServerImport.Server>) {
    private val ruleSources=GeoDataConfig.orderedRules(settings).let {rules->(0 until rules.length()).map {rules.getJSONObject(it)}}+listOfNotNull(settings.optJSONObject(RoutingConnectionBinding.DEFAULT))
    private val bound=RoutingConnectionBinding.enabled(settings)&&settings.has(RoutingConnectionBinding.RUNTIME)
    private data class Pool(val ids:List<String>,val interval:Int,val tolerance:Int,val tag:String,val managed:Boolean)
    private val pools=ruleSources.let {rules->rules
        .filter {it.optBoolean("enabled",true)&&it.has(POOL)}.map {Pool(ids(it).sorted(),interval(it),tolerance(it),outbound(it,""),bound||it.has(AUTO))}.distinct()}
    private val supervised=settings.has(RoutingConnectionBinding.RUNTIME)
    private val poolInitial=if(supervised)pools.filter {it.managed}.associateWith{RoutingConnectionBinding.initial(settings,it.ids,it.tag)}else emptyMap()
    private val fixed=ruleSources.let {rules->rules
        .filter {it.optBoolean("enabled",true)&&it.has(KEY)}.map {it.getString(KEY)}.toSet()}
    fun poolOnly(id:String)=id !in fixed&&pools.any {id in it.ids}
    private val captured:List<ServerImport.Server>
    val entries:List<ServerImport.Server> get()=java.util.Collections.unmodifiableList(captured.map {copy(it)})
    val requiresProtocolDispatcher:Boolean get()=captured.any {it.carrier==null}
    init {
        val ids=activeIds(settings)
        val available=catalogue.filter {it.id in ids}.groupBy {it.id}
        captured=ids.map {id->
            val entry=available[id]?.singleOrNull()
                ?:throw IllegalArgumentException("Сервер правила удалён или изменён. Выберите его заново в правиле маршрутизации.")
            require(selectable(entry)||bound&&RoutingConnectionBinding.eligible(entry,entry.whitelists)) {"Сервер «${entry.name}» нельзя использовать отдельно в правиле: нужен одиночный профиль без собственной конфигурации маршрутов."}
            copy(entry)
        }
        require(captured.count {it.carrier!=null}<=8) {"В правилах допускается до 8 дополнительных транспортов одновременно."}
    }
    fun append(source:JSONObject):JSONObject {
        val config=JSONObject(source.toString())
        if(captured.isEmpty())return config
        val outputs=config.getJSONArray("outbounds")
        val endpoints=config.optJSONArray("endpoints")?:JSONArray()
        val tags=linkedSetOf<String>()
        for(nodes in listOf(outputs,endpoints))repeat(nodes.length()){i->
            require(tags.add(nodes.getJSONObject(i).getString("tag"))) {"Теги выходов маршрутизации повторяются."}
        }
        captured.forEach {entry->
            val tag=tag(entry.id);require(tags.add(tag)) {"Тег сервера правила занят исходным профилем."}
            val node=if(entry.carrier!=null)JSONObject().put("type","socks").put("server","127.0.0.1")
                .put("server_port",1).put("version","5").put("username",java.util.UUID.randomUUID().toString())
                .put("password",java.util.UUID.randomUUID().toString())
                else JSONObject(requireNotNull(entry.outbound?:entry.endpoint).toString())
            node.put("tag",tag)
            if(entry.endpoint!=null&&entry.carrier==null)endpoints.put(node)else outputs.put(node)
        }
        pools.forEach {pool->
            val ids=pool.ids
            val tag=pool.tag;require(tags.add(tag)) {"Тег группы серверов правила занят исходным профилем."}
            outputs.put(if(supervised&&poolInitial.containsKey(pool))JSONObject().put("type","selector").put("tag",tag).put("outbounds",JSONArray(ids.map{RouteServerPlan.tag(it)})).put("default",RouteServerPlan.tag(poolInitial.getValue(pool))).put("interrupt_exist_connections",false)
                else JSONObject().put("type","urltest").put("tag",tag).put("outbounds",JSONArray(ids.map {RouteServerPlan.tag(it)}))
                    .put("url","https://www.gstatic.com/generate_204").put("interval",if(pool.interval==60)"1m"else"${pool.interval}s").put("tolerance",pool.tolerance).put("interrupt_exist_connections",false))
        }
        if(endpoints.length()>0)config.put("endpoints",endpoints)
        return config
    }
    override fun toString()="RouteServerPlan(servers=${captured.size})"
    companion object {
        const val KEY="serverId"
        const val POOL="serverIds"
        const val AUTO="serverAuto"
        private fun interval(rule:JSONObject)=rule.optJSONObject(AUTO)?.getInt("interval")?:60
        private fun tolerance(rule:JSONObject)=rule.optJSONObject(AUTO)?.getInt("tolerance")?:0
        fun fastestEntries(entries:List<ServerImport.Server>,bs:Boolean)=entries.filter {
            selectable(it)&&ConnectionSections.belongs(it,bs)&&it.protocol !in AutoSelection.AUTOMATIC_EXCLUDED
        }.distinctBy {it.id}
        private fun copy(entry:ServerImport.Server)=ServerImport.Server(entry.original,entry.name,entry.protocol,entry.host,entry.port,
            entry.outbound?.let {JSONObject(it.toString())},entry.endpoint?.let {JSONObject(it.toString())},
            entry.configuration?.let {JSONObject(it.toString())},entry.carrier?.let {JSONObject(it.toString())},entry.profileDns.toList(),entry.whitelists)
        fun validate(rule:JSONObject) {
            if(rule.has(AUTO)) {
                require(rule.has(POOL)){"Автовыбор правила требует группу серверов."}
                val settings=rule.getJSONObject(AUTO)
                require(settings.keys().asSequence().all {it in setOf("interval","tolerance","mode","failureOnly","preferred")}){"Некорректные настройки автовыбора правила."}
                require(settings.getInt("interval") in 5..86400&&settings.getInt("tolerance") in 0..10000){"Неверный интервал или отклонение автовыбора правила."}
                require(settings.optString("mode",BsAutoSelectionPolicy.FASTEST) in RouteEditorAuto.modes){"Неизвестный режим автовыбора правила."}
                if(settings.has("failureOnly"))require(settings.get("failureOnly") is Boolean)
                if(settings.has("preferred"))require(settings.getString("preferred") in (0 until rule.getJSONArray(POOL).length()).map {rule.getJSONArray(POOL).getString(it)}){"Предпочтительный сервер отсутствует в правиле."}
            }
            if(!rule.has(KEY)&&!rule.has(POOL))return
            require(!(rule.has(KEY)&&rule.has(POOL))) {"Выберите один сервер или группу, не оба варианта сразу."}
            val values=if(rule.has(POOL))rule.getJSONArray(POOL).let {a->
                require(a.length() in 2..32) {"Группа правила: от 2 до 32 серверов."}
                (0 until a.length()).map {a.get(it)}
            }else listOf(rule.get(KEY))
            require(values.all {it is String&&it.matches(Regex("[0-9a-f]{64}"))}&&values.distinct().size==values.size) {"Некорректные или повторяющиеся серверы правила маршрутизации."}
            require(rule.optString("target","proxy")=="proxy") {"Отдельный сервер выбирается только для выхода VPN / прокси."}
        }
        fun activeIds(settings:JSONObject):List<String> {
            val ids=linkedSetOf<String>();val rules=GeoDataConfig.orderedRules(settings)
            repeat(rules.length()){index->val rule=rules.getJSONObject(index);validate(rule)
                if(rule.optBoolean("enabled",true))ids.addAll(RouteServerPlan.ids(rule))
            }
            settings.optJSONObject(RoutingConnectionBinding.DEFAULT)?.let{ids.addAll(RouteServerPlan.ids(it))}
            require(ids.size<=32) {"В правилах допускается до 32 разных серверов."}
            return ids.toList()
        }
        fun tag(id:String):String {require(id.matches(Regex("[0-9a-f]{64}")));return "mcrf-route-$id"}
        fun ids(rule:JSONObject):List<String> {
            validate(rule)
            return rule.optJSONArray(POOL)?.let {a->(0 until a.length()).map {a.getString(it)}}
                ?:rule.optString(KEY).takeIf(String::isNotEmpty)?.let(::listOf)?:emptyList()
        }
        internal fun poolTag(ids:List<String>,interval:Int=60,tolerance:Int=0):String {
            require(ids.size in 2..32)
            ids.forEach(::tag)
            val identity=ids.sorted().joinToString(",")+if(interval==60&&tolerance==0)""else"|$interval|$tolerance"
            val hash=java.security.MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
                .joinToString(""){"%02x".format(it.toInt() and 255)}
            return "mcrf-route-pool-$hash"
        }
        fun outbound(rule:JSONObject,default:String):String {
            validate(rule)
            if(rule.has(POOL)) {
                val tag=poolTag(ids(rule),interval(rule),tolerance(rule))
                val auto=rule.optJSONObject(AUTO)?:return tag
                val identity=listOf(auto.optString("mode",BsAutoSelectionPolicy.FASTEST),auto.optBoolean("failureOnly",false).toString(),auto.optString("preferred"),if(auto.optString("mode")==AutoSelection.SEQUENTIAL)ids(rule).joinToString(",")else"").joinToString("|")
                val suffix=java.security.MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8)).take(8).joinToString(""){"%02x".format(it.toInt() and 255)}
                return "$tag-$suffix"
            }
            return if(rule.has(KEY))tag(rule.getString(KEY))else default
        }
        fun selectable(entry:ServerImport.Server):Boolean {
            if(entry.protocol=="openflux"||entry.configuration!=null)return false
            // A saved SERVER must be self-contained. A selector/detour could
            // otherwise resolve against an unrelated main-profile tag and send
            // this rule through a different server without the user's choice.
            if(listOf(entry.carrier,entry.outbound,entry.endpoint).count {it!=null}!=1)return false
            entry.outbound?.let {node->
                if(node.optString("type") in setOf("","direct","block","dns","selector","urltest")||node.has("detour"))return false
            }
            entry.endpoint?.let {node->
                // Route endpoints are userspace only: no additional system TUN.
                if(node.optString("type")!="wireguard"||node.optBoolean("system")||node.has("detour"))return false
            }
            return true
        }
        fun requireSingleServerCore(settings:JSONObject) {
            require(activeIds(settings).isEmpty()) {"Для отдельных серверов правил нужен установленный внешний диспетчер sing-box. Основной выбор и правила не изменены; для WG/AWG действует заданный приоритет работы через установленный sing-box."}
        }
    }
}
