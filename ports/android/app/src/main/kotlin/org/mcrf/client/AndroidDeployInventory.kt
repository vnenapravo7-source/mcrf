package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

/** Local inventory only. Reading/migrating it never contacts a VPS or runs a command.
 * Credentials and complete results stay in the existing encrypted deploy store.
 * Protocol, host display name and user-assigned name are NOT instance identities. */
object AndroidDeployInventory {
    const val SECTION="deployInventory"
    private val ids=Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private fun id(kind:String,key:String)=UUID.nameUUIDFromBytes(("mcrf.deploy.$kind\u0000"+key).toByteArray(Charsets.UTF_8)).toString()
    private fun map(root:JSONObject,key:String)=root.getJSONObject(key)
    fun empty()=JSONObject().put("schema",1).put("servers",JSONObject()).put("panels",JSONObject()).put("instances",JSONObject())
    fun name(value:String):String {
        val clean=value.trim()
        require(clean.isNotEmpty()&&clean.length<=80&&clean.none(Char::isISOControl)){"Название: от 1 до 80 символов без управляющих знаков"}
        return clean
    }
    /** Idempotent upgrade: old operation history is retained; repeated calls and
     * later results do not replace custom names or merge same-protocol instances. */
    fun fromHistory(current:JSONObject,results:JSONObject):JSONObject {
        val root=if(current.length()==0)empty()else JSONObject(current.toString()).also(::validate)
        for(entry in AndroidDeployHistory.entries(results)){
            val template=entry.result.optJSONObject("transport_template")?:continue
            val host=template.getString("host").lowercase(Locale.ROOT)
            val port=template.getString("sshPort").toInt()
            val serverId=id("server","$host:$port")
            val servers=map(root,"servers")
            if(!servers.has(serverId))servers.put(serverId,JSONObject().put("name",host.take(80)).put("host",host).put("sshPort",port))
            val panelUrl=entry.result.optString("panel_url")
            val panelId=if(panelUrl.isBlank())"" else id("panel",serverId+"\u0000"+panelUrl)
            if(panelId.isNotEmpty()&&!map(root,"panels").has(panelId)){
                map(root,"panels").put(panelId,JSONObject().put("name",(entry.recipe+" · "+host).take(80)).put("serverId",serverId)
                    .put("url",panelUrl).put("resultKey",entry.key).put("recipe",entry.recipe))
            }
            val instanceId=entry.result.optString("deployment_id").ifBlank {id("instance",entry.key)}
            val instances=map(root,"instances")
            if(!instances.has(instanceId))instances.put(instanceId,JSONObject().put("name",entry.result.optString("deployment_name").ifBlank {(entry.recipe+" · "+host).take(80)})
                .put("recipe",entry.recipe).put("serverId",serverId).put("panelId",panelId).put("resultKeys",JSONArray().put(entry.key)))
            else {
                val instance=instances.getJSONObject(instanceId)
                require(instance.getString("recipe")==entry.recipe&&instance.getString("serverId")==serverId&&instance.getString("panelId")==panelId){"Одна установка не может ссылаться на разные серверы или панели"}
                val keys=instance.getJSONArray("resultKeys")
                if((0 until keys.length()).none {keys.getString(it)==entry.key})keys.put(entry.key)
            }
        }
        validate(root)
        return root
    }
    fun rename(current:JSONObject,kind:String,id:String,value:String):JSONObject {
        require(kind in setOf("servers","panels","instances"))
        val root=JSONObject(current.toString()).also(::validate)
        map(root,kind).getJSONObject(id).put("name",name(value))
        validate(root);return root
    }
    fun validate(root:JSONObject){
        require(root.keys().asSequence().toSet()==setOf("schema","servers","panels","instances")&&root.get("schema")==1)
        val fields=mapOf("servers" to setOf("name","host","sshPort"),"panels" to setOf("name","serverId","url","resultKey","recipe"),
            "instances" to setOf("name","recipe","serverId","panelId","resultKeys"))
        for((kind,expected) in fields){
            val entries=map(root,kind);require(entries.length()<=128)
            for(key in entries.keys()){
                require(ids.matches(key));val row=entries.getJSONObject(key)
                require(row.keys().asSequence().toSet()==expected)
                require(row.get("name") is String&&name(row.getString("name"))==row.getString("name"))
                if(kind=="servers"){
                    require(row.get("host") is String&&AndroidDeployPlan.host(row.getString("host")))
                    require(row.get("sshPort") is Int&&row.getInt("sshPort") in 1..65535)
                }else{
                    require(row.get("serverId") is String&&map(root,"servers").has(row.getString("serverId")))
                    require(row.get("recipe") is String&&AndroidDeployRecipe.all.any {it.id==row.getString("recipe")})
                    if(kind=="panels"){
                        val url=row.getString("url");val uri=java.net.URI(url)
                        require(url.length<=8192&&url.none(Char::isISOControl)&&uri.scheme in setOf("http","https")&&uri.host!=null&&uri.userInfo==null)
                        require(row.get("resultKey") is String&&row.getString("resultKey").length in 1..400)
                    }else{
                        val panel=row.getString("panelId")
                        require(panel.isEmpty()||map(root,"panels").getJSONObject(panel).getString("serverId")==row.getString("serverId"))
                        val keys=row.getJSONArray("resultKeys");require(keys.length() in 1..128)
                        val seen=HashSet<String>();repeat(keys.length()){
                            require(keys.get(it) is String);val result=keys.getString(it)
                            require(result.length in 1..400&&result.none(Char::isISOControl)&&seen.add(result))
                        }
                    }
                }
            }
        }
    }
}
