package org.mcrf.client

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Derived encrypted cache of the requested/started VPN-section session, never
 * a new/random server. A pre-start BS diversion retains the original VPN too. */
object AutoVpnReturn {
    const val KEY="autoVpnReturn"
    class Saved(val template:JSONObject,val vpn:Boolean,val port:Int,val routes:Boolean,val core:String,val ids:Set<String>)
    private fun store(context:Context)=ProfileStore(context,"preferences.enc")
    fun save(context:Context,template:JSONObject,vpn:Boolean,port:Int,routes:Boolean,core:String) {
        if(template.has("mcrf_carrier")||template.has("mcrf_bs"))return
        val catalogue=Connections(context).apply {load()}
        val tags=template.optJSONArray("outbounds")?.let {a->(0 until a.length()).mapNotNull {a.optJSONObject(it)?.optString("tag")}.toSet()}?:emptySet()
        val ids=catalogue.entries().filter {it.carrier==null&&ServerImport.tag(it) in tags}.map {it.id}
        val raw=JSONObject().put("version",1).put("template",JSONObject(template.toString())).put("vpn",vpn)
            .put("port",port).put("routes",routes).put("core",core).put("ids",JSONArray(ids))
        validate(raw)
        synchronized(ProfileStore.storageLock){
            val encrypted=store(context);val previous=encrypted.read().ifBlank {"{}"}
            val root=JSONObject(previous)
            if(root.optJSONObject(KEY)?.toString()!=raw.toString()){root.put(KEY,raw);encrypted.write(root.toString())}
        }
    }
    fun validate(value:JSONObject) {
        require(value.toString().toByteArray(Charsets.UTF_8).size<=4*1024*1024)
        require(value.keys().asSequence().toSet()==setOf("version","template","vpn","port","routes","core","ids"))
        require(value.get("version") is Int&&value.getInt("version")==1)
        val template=value.getJSONObject("template")
        require(!template.has("mcrf_bs")&&!template.has("mcrf_carrier"))
        require(value.get("port") is Int&&value.getInt("port") in 1..65535)
        require(value.get("vpn") is Boolean&&value.get("routes") is Boolean)
        require(value.getString("core") in setOf("singbox","steer"))
        val ids=value.getJSONArray("ids");require(ids.length()<=1000)
        repeat(ids.length()){require(ids.get(it) is String&&ids.getString(it).matches(Regex("[0-9a-f]{64}")))}
    }
    fun load(context:Context):Saved? {
        val raw=store(context).read();if(raw.isBlank())return null
        val value=JSONObject(raw).optJSONObject(KEY)?:return null
        validate(value)
        val template=value.getJSONObject("template")
        val port=value.getInt("port")
        val ids=value.getJSONArray("ids").let {a->require(a.length()<=1000);(0 until a.length()).map {a.getString(it)}.toSet()}
        require(ids.all {it.matches(Regex("[0-9a-f]{64}"))})
        val current=Connections(context).apply {load()}.entries().filter {it.carrier==null}.mapTo(hashSetOf()){it.id}
        require(current.containsAll(ids)) {"Сохранённые VPN-серверы изменены или удалены."}
        val core=value.getString("core")
        require(core==ClientPrimarySelection.selected(context)) {"Основное ядро изменено; сначала подключи VPN вручную."}
        return Saved(JSONObject(template.toString()),value.getBoolean("vpn"),port,value.getBoolean("routes"),core,ids)
    }
}
