package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject

/** Separate listeners and call groups for each selected protocol; never reuse one-use calls implicitly. */
object AndroidDeploySwg {
    val kinds=listOf("qwdtt","wdtt","csqtt","csqtt25")
    fun isCsqtt(kind:String)=kind in setOf("csqtt","csqtt25")
    fun callKind(kind:String)=if(kind=="csqtt25")"csqtt"else kind
    fun label(kind:String)=when(kind){"qwdtt"->"QWDTT · RAW";"csqtt"->"CSQTT 2.1.9";"csqtt25"->"CSQTT 2.5";else->kind.uppercase()}
    fun validateCallArray(calls:JSONArray) {
        require(calls.toString().toByteArray(Charsets.UTF_8).size<=65536) {"Список звонков больше 64 КБ"}
        repeat(calls.length()){require(calls.get(it) is String && calls.getString(it).length<=1024)}
    }
    fun drafts(options:JSONObject):JSONArray = options.optJSONArray("swg_profiles")?.let {previous->
        JSONArray(previous.toString()).apply {
            if((0 until length()).none {getJSONObject(it).optString("kind")=="csqtt25"}){
                val occupied=(0 until length()).flatMap {index->listOf("relay_port","wg_port").map {getJSONObject(index).optString(it)}}.toSet()
                var relay=56004;while(relay.toString() in occupied)relay+=10
                var wg=51823;while(wg.toString() in occupied||wg==relay)wg+=10
                put(JSONObject().put("kind","csqtt25").put("enabled",false).put("relay_port",relay.toString()).put("wg_port",wg.toString()).put("call_count",6).put("vk_calls",JSONArray()))
            }
        }
    } ?: JSONArray().apply {
        val legacy=options.optString("relay_kind","qwdtt")
        val reserved=mutableSetOf(options.optString("relay_port","56000"),options.optString("wg_port","51820"),"56003")
        fun available(start:Int):String {var port=start;while(port.toString() in reserved)port+=10;return port.toString().also(reserved::add)}
        kinds.forEachIndexed { index,kind ->
            val selected=kind==legacy
            put(JSONObject().put("kind",kind).put("enabled",selected)
                .put("relay_port",if(selected)options.optString("relay_port","56000") else available(56000+index))
                .put("wg_port",if(selected)options.optString("wg_port","51820") else available(51820+index))
                .put("call_count",if(selected)options.optInt("call_count",6) else 6)
                .put("vk_calls",if(selected) options.optJSONArray("vk_calls") ?: JSONArray() else JSONArray()))
        }
    }
    fun validateDrafts(array:JSONArray,allowGroups:Boolean=false) {
        require(array.length() in 1..4) {"Выберите от одного до четырёх вариантов SWG"}
        val seen=hashSetOf<String>()
        repeat(array.length()) { i ->
            val p=array.getJSONObject(i)
            require(p.keys().asSequence().all {it in setOf("kind","enabled","relay_port","wg_port","call_count","vk_calls") || allowGroups&&it=="call_group"})
            val kind=p.getString("kind");require(kind in kinds && seen.add(kind)) {"Повторяющийся или неизвестный протокол SWG"}
            require(p.get("enabled") is Boolean)
            for(key in listOf("relay_port","wg_port"))require((p.get(key) is Int || p.get(key) is String)&&p.get(key).toString().length<=8)
            require(p.get("call_count") is Int);VkRelayProtocol.count(p.getInt("call_count"),callKind(kind))
            // Bound payload bytes, not the number of calls; opening a draft never truncates it.
            validateCallArray(p.getJSONArray("vk_calls"))
            if(p.has("call_group"))require(p.get("call_group") is String&&Regex("[A-Za-z0-9_.:-]{1,128}").matches(p.getString("call_group")))
        }
    }
    fun profiles(options:JSONObject):List<JSONObject> {
        val all=drafts(options);validateDrafts(all,true)
        val selected=(0 until all.length()).map(all::getJSONObject).filter {it.getBoolean("enabled")}
        require(selected.isNotEmpty()) {"Выберите хотя бы один протокол SWG"}
        val ports=hashSetOf<Int>().apply {if(selected.any {it.getString("kind")=="qwdtt"})add(56003)}
        return selected.map { raw -> JSONObject(raw.toString()).apply {
            for(key in if(isCsqtt(getString("kind")))listOf("relay_port") else listOf("relay_port","wg_port")) {
                val port=get(key).toString().toIntOrNull()
                require(port!=null && port in 1024..65535 && ports.add(port)) {"У протоколов SWG должны быть разные свободные порты (1024–65535)"}
                put(key,port)
            }
        } }
    }
    fun ports(options:JSONObject)=profiles(options).flatMap { p -> listOf(p.getInt("relay_port")) +
        (if(isCsqtt(p.getString("kind")))emptyList() else listOf(p.getInt("wg_port"))) +
        (if(p.getString("kind")=="qwdtt")listOf(56003)else emptyList()) }
    fun validateCalls(options:JSONObject) {
        val used=hashSetOf<String>()
        profiles(options).forEach { p ->
            val calls=p.getJSONArray("vk_calls")
            if(calls.length()>0)VkRelayProtocol.calls((0 until calls.length()).map(calls::getString),callKind(p.getString("kind"))).forEach {require(used.add(VkRelayProtocol.hash(it))) {"Для разных протоколов нужны отдельные звонки VK"}}
            else require(options.optBoolean("automatic_calls")) {"Укажите звонки для ${p.getString("kind").uppercase()} или включите их автоматическое создание"}
        }
    }
    fun groupId(operation:String,kind:String)="deploy:$operation:$kind"
    fun validateSaved(array:JSONArray,operation:String,groups:JSONObject) {
        validateDrafts(array,true)
        val options=JSONObject().put("swg_profiles",array)
        validateCalls(options)
        profiles(options).forEach { p ->
            if(p.has("call_group")) {
                val id=p.getString("call_group");require(id==groupId(operation,p.getString("kind")))
                val group=groups.getJSONObject(id);val hashes=group.getJSONArray("hashes");val calls=p.getJSONArray("vk_calls")
                require(group.getString("kind")==callKind(p.getString("kind"))&&group.optInt("count",6)==p.getInt("call_count")&&hashes.length()==calls.length())
                require((0 until calls.length()).all {VkRelayProtocol.hash(calls.getString(it))==hashes.getString(it)})
            }
        }
    }
}
