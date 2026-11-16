package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject

/** The active group's fields stay at the root for every existing route/DNS compiler.
 * Inactive groups are stored separately and never enter runtime preparation. */
internal object RoutingGroups {
    const val KEY="routingGroups"
    val ids=listOf("vpn","bs","auto")
    private val names=listOf("VPN","БС","Авто VPN + БС")
    private val fields=setOf("rules","defaultTarget","geoData","remoteRouting")
    private val dnsFields=setOf("globalDns","advancedDns")
    private fun imported(id:String)=id.matches(Regex("profile-[a-f0-9]{32}"))
    private fun capture(state:JSONObject)=JSONObject().also {out->(fields+if(imported(active(state)))dnsFields else emptySet()).forEach {key->
        if(state.has(key))out.put(key,copy(state.get(key)))
    }}
    private fun copy(value:Any):Any=when(value){is JSONObject->JSONObject(value.toString());is JSONArray->JSONArray(value.toString());else->value}
    /** Root fields already describe the active group. Storage/navigation metadata is
     * not a native setting; keep every other field so unsupported effects fail closed. */
    fun runtime(state:JSONObject)=TemporaryRulesRuntime.project(state).let {effective->JSONObject().also {out->effective.keys().forEach {key->
        if(key!=KEY&&key!=LegacyRoutingMigration.KEY&&key!=RoutingConnectionBinding.KEY&&key!=RoutingConnectionBinding.RUNTIME&&key!=RoutingConnectionBinding.DEFAULT)out.put(key,copy(effective.get(key)))
    }}}
    fun ensure(state:JSONObject) {
        // Repair absent fields only. A saved Direct/Block choice is authoritative,
        // including in an empty group; its provenance cannot be inferred from age.
        defaults(state)
        if(state.has(KEY)){
            val root=state.getJSONObject(KEY);val list=root.getJSONArray("groups")
            repeat(list.length()){i->val group=list.getJSONObject(i)
                if(group.getString("id")!=root.getString("active"))defaults(group.getJSONObject("data"))
            }
            return
        }
        state.put(KEY,JSONObject().put("active",ids.first()).put("groups",JSONArray(ids.mapIndexed {i,id->
            JSONObject().put("id",id).put("name",names[i]).put("data",if(i==0)JSONObject()else JSONObject().put("rules",JSONArray()).put("defaultTarget","proxy"))
        })))
    }
    private fun defaults(data:JSONObject){
        if(!data.has("rules"))data.put("rules",JSONArray())
        if(!data.has("defaultTarget"))data.put("defaultTarget","proxy")
    }
    fun active(state:JSONObject)=state.optJSONObject(KEY)?.optString("active")?:ids.first()
    fun groups(state:JSONObject):List<Pair<String,String>> {
        ensure(state)
        val list=state.getJSONObject(KEY).getJSONArray("groups")
        return (0 until list.length()).map {list.getJSONObject(it).let {g->g.getString("id") to g.getString("name")}}
    }
    fun synchronize(state:JSONObject) {
        ensure(state)
        val list=state.getJSONObject(KEY).getJSONArray("groups")
        val group=(0 until list.length()).map {list.getJSONObject(it)}.single {it.getString("id")==active(state)}
        group.put("data",JSONObject())
    }
    fun select(state:JSONObject,id:String):Boolean {
        ensure(state)
        require(groups(state).any {it.first==id})
        if(active(state)==id)return false
        val root=state.getJSONObject(KEY);val list=root.getJSONArray("groups")
        (0 until list.length()).map {list.getJSONObject(it)}.single {it.getString("id")==active(state)}.put("data",capture(state))
        val data=(0 until list.length()).map {list.getJSONObject(it)}.single {it.getString("id")==id}.getJSONObject("data")
        if(imported(id)&&!imported(active(state)))root.put("ordinaryDns",JSONObject().also {out->dnsFields.forEach {key->if(state.has(key))out.put(key,copy(state.get(key)))}})
        if(imported(active(state))) {
            dnsFields.forEach(state::remove)
            if(!imported(id))root.getJSONObject("ordinaryDns").let {dns->dns.keys().forEach {key->state.put(key,copy(dns.get(key))) };root.remove("ordinaryDns")}
        }
        fields.forEach(state::remove)
        data.keys().forEach {key->state.put(key,copy(data.get(key)))}
        defaults(state)
        root.put("active",id)
        synchronize(state)
        return true
    }
    fun next(state:JSONObject):String=groups(state).map {it.first}.let {all->all[(all.indexOf(active(state))+1)%all.size]}
    /** Each imported profile owns its DNS and an independent geodata namespace. */
    fun add(state:JSONObject,name:String,data:JSONObject):String {
        ensure(state)
        val list=state.getJSONObject(KEY).getJSONArray("groups");require(list.length()<35){"Можно хранить до 32 импортированных профилей."}
        val id="profile-"+java.util.UUID.randomUUID().toString().replace("-","")
        require(data.keys().asSequence().all {it in fields+dnsFields}&&data.has("globalDns")&&data.has("advancedDns"))
        val candidate=JSONObject(data.toString());defaults(candidate)
        candidate.optJSONObject("geoData")?.put("cacheNamespace",id)
        val check=JSONObject(state.toString()).apply {remove(KEY);fields.forEach(::remove);dnsFields.forEach(::remove);candidate.keys().forEach {put(it,copy(candidate.get(it)))}}
        RoutingStore.validate(check)
        val trimmed=name.trim();require(trimmed.isNotEmpty()&&trimmed.length<=40&&trimmed.none(Char::isISOControl))
        list.put(JSONObject().put("id",id).put("name",trimmed).put("data",candidate))
        return id
    }
    fun isImported(id:String)=imported(id)
    fun remove(state:JSONObject,id:String) {
        require(imported(id)){"Встроенную группу нельзя удалить."}
        ensure(state)
        val root=state.getJSONObject(KEY);val list=root.getJSONArray("groups")
        require((0 until list.length()).any {list.getJSONObject(it).getString("id")==id})
        if(active(state)==id)select(state,ids.first())
        root.put("groups",JSONArray((0 until list.length()).map {list.getJSONObject(it)}.filter {it.getString("id")!=id}))
    }
    /** Restore shared DNS while moving between the Simple and Expert banks. */
    fun suspendDns(state:JSONObject) {
        val root=state.optJSONObject(KEY)?:return
        if(!imported(active(state)))return
        val group=root.getJSONArray("groups").let {a->(0 until a.length()).map(a::getJSONObject).single {it.getString("id")==active(state)}}
        group.put("data",capture(state))
        dnsFields.forEach(state::remove)
        root.getJSONObject("ordinaryDns").let {dns->dns.keys().forEach {state.put(it,copy(dns.get(it)))}}
        root.remove("ordinaryDns")
    }
    fun resumeDns(state:JSONObject) {
        val root=state.optJSONObject(KEY)?:return
        if(!imported(active(state)))return
        root.put("ordinaryDns",JSONObject().also {out->dnsFields.forEach {if(state.has(it))out.put(it,copy(state.get(it)))}})
        val data=root.getJSONArray("groups").let {a->(0 until a.length()).map(a::getJSONObject).single {it.getString("id")==active(state)}}.getJSONObject("data")
        dnsFields.forEach(state::remove);dnsFields.forEach {if(data.has(it))state.put(it,copy(data.get(it)))}
    }
    fun rename(state:JSONObject,id:String,name:String) {
        require(groups(state).any {it.first==id}&&name.trim().isNotEmpty()&&name.trim().length<=40&&name.none(Char::isISOControl)) {"Название группы: от 1 до 40 символов без управляющих знаков."}
        ensure(state)
        val list=state.getJSONObject(KEY).getJSONArray("groups")
        (0 until list.length()).map {list.getJSONObject(it)}.single {it.getString("id")==id}.put("name",name.trim())
    }
    fun validate(state:JSONObject) {
        val root=state.optJSONObject(KEY)?:return
        require(root.keys().asSequence().all {it in setOf("active","groups","ordinaryDns")})
        require(root.has("ordinaryDns")==imported(active(state)))
        root.optJSONObject("ordinaryDns")?.let {dns->
            require(dns.keys().asSequence().all {it in dnsFields}&&dns.has("globalDns"))
            DnsConfig.build(dns.getJSONObject("globalDns"),"d","b")
            dns.optJSONObject("advancedDns")?.let(AdvancedDnsConfig::validate)
        }
        val list=root.getJSONArray("groups");require(list.length() in 3..35)
        val all=(0 until list.length()).map {list.getJSONObject(it).getString("id")}
        require(all.take(3)==ids&&all.drop(3).all(::imported)&&all.distinct().size==all.size&&active(state) in all)
        repeat(list.length()){i->val group=list.getJSONObject(i)
            require(group.keys().asSequence().toSet()==setOf("id","name","data"))
            val name=group.getString("name");require(name.isNotBlank()&&name.length<=40&&name.none(Char::isISOControl))
            val data=group.getJSONObject("data")
            val profileId=group.getString("id")
            if(imported(profileId)) {
                val settings=if(profileId==active(state))state else data
                require(settings.has("globalDns")&&settings.has("advancedDns"))
                settings.optJSONObject("geoData")?.let {require(it.getString("cacheNamespace")==profileId)}
            }
            if(profileId==active(state)){require(data.length()==0);return@repeat}
            require(data.keys().asSequence().all {it in (fields+if(imported(profileId))dnsFields else emptySet())}&&data.has("rules")&&data.has("defaultTarget"))
            // Validate inactive lists too, using the shared DNS/app policies but
            // without recursively activating another group's storage envelope.
            val candidate=JSONObject().also {out->state.keys().forEach {key->if(key!=KEY&&key !in fields)out.put(key,copy(state.get(key)))}}
            data.keys().forEach {key->candidate.put(key,copy(data.get(key)))}
            RoutingStore.validate(candidate)
        }
    }
}
