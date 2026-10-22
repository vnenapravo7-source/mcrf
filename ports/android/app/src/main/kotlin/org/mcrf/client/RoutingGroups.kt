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
    private fun capture(state:JSONObject)=JSONObject().also {out->fields.forEach {key->
        if(state.has(key))out.put(key,copy(state.get(key)))
    }}
    private fun copy(value:Any):Any=when(value){is JSONObject->JSONObject(value.toString());is JSONArray->JSONArray(value.toString());else->value}
    fun ensure(state:JSONObject) {
        if(state.has(KEY))return
        state.put(KEY,JSONObject().put("active",ids.first()).put("groups",JSONArray(ids.mapIndexed {i,id->
            JSONObject().put("id",id).put("name",names[i]).put("data",if(i==0)JSONObject()else JSONObject().put("rules",JSONArray()).put("defaultTarget","proxy"))
        })))
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
        require(id in ids)
        ensure(state)
        if(active(state)==id)return false
        val root=state.getJSONObject(KEY);val list=root.getJSONArray("groups")
        (0 until list.length()).map {list.getJSONObject(it)}.single {it.getString("id")==active(state)}.put("data",capture(state))
        val data=(0 until list.length()).map {list.getJSONObject(it)}.single {it.getString("id")==id}.getJSONObject("data")
        fields.forEach(state::remove)
        data.keys().forEach {key->state.put(key,copy(data.get(key)))}
        root.put("active",id)
        synchronize(state)
        return true
    }
    fun next(state:JSONObject):String=ids[(ids.indexOf(active(state))+1)%ids.size]
    fun rename(state:JSONObject,id:String,name:String) {
        require(id in ids&&name.trim().isNotEmpty()&&name.trim().length<=40&&name.none(Char::isISOControl)) {"Название группы: от 1 до 40 символов без управляющих знаков."}
        ensure(state)
        val list=state.getJSONObject(KEY).getJSONArray("groups")
        (0 until list.length()).map {list.getJSONObject(it)}.single {it.getString("id")==id}.put("name",name.trim())
    }
    fun validate(state:JSONObject) {
        val root=state.optJSONObject(KEY)?:return
        require(root.keys().asSequence().toSet()==setOf("active","groups")&&root.getString("active") in ids)
        val list=root.getJSONArray("groups");require(list.length()==3)
        require((0 until 3).map {list.getJSONObject(it).getString("id")}==ids)
        repeat(3){i->val group=list.getJSONObject(i)
            require(group.keys().asSequence().toSet()==setOf("id","name","data"))
            val name=group.getString("name");require(name.isNotBlank()&&name.length<=40&&name.none(Char::isISOControl))
            val data=group.getJSONObject("data")
            if(group.getString("id")==active(state)){require(data.length()==0);return@repeat}
            require(data.keys().asSequence().all {it in fields}&&data.has("rules")&&data.has("defaultTarget"))
            // Validate inactive lists too, using the shared DNS/app policies but
            // without recursively activating another group's storage envelope.
            val candidate=JSONObject().also {out->state.keys().forEach {key->if(key!=KEY&&key !in fields)out.put(key,copy(state.get(key)))}}
            data.keys().forEach {key->candidate.put(key,copy(data.get(key)))}
            RoutingStore.validate(candidate)
        }
    }
}
