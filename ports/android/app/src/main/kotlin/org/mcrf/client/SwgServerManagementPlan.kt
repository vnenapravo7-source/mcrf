package org.mcrf.client

import org.json.JSONObject

/** HTTP mapping reviewed against SWG e58d2a9. Execution must separately verify SSH/TLS identity. */
object SwgServerManagementPlan {
    // Reviewed users/update rebuild drops the user's disabled flag. Expiry is
    // still required, but must not be advertised until a preserving API exists.
    val implementedActions=setOf("status","clients","interfaces","add-client","disable-client","restart")
    class Request(val method:String,val path:String,private val payload:String?) {
        fun body():JSONObject?=payload?.let(::JSONObject)
        override fun toString()="SwgManagementRequest($method,$path,body=redacted)"
    }
    fun build(request:JSONObject,confirmed:Boolean=false):Request {
        require(request.optInt("schema")==1&&request.optString("provider")=="swg")
        require(request.keys().asSequence().toSet()==setOf("schema","request_id","provider","action","parameters"))
        val action=ServerManagementRequest.Action.values().single {it.id==request.getString("action")}
        val parameters=request.getJSONObject("parameters")
        // Confirmation must be supplied by the live UI/controller, never read from a saved JSON flag.
        ServerManagementRequest.build("swg",action,implementedActions,parameters,confirmed=confirmed)
        return when(action){
            ServerManagementRequest.Action.STATUS->Request("GET","/api/v1/summary",null)
            ServerManagementRequest.Action.CLIENTS->Request("GET","/api/state",null)
            ServerManagementRequest.Action.INTERFACES->Request("GET","/api/state",null)
            ServerManagementRequest.Action.RESTART->Request("POST","/api/iface/restart",JSONObject(parameters.toString()).toString())
            ServerManagementRequest.Action.ADD_CLIENT->Request("POST","/api/users/create",JSONObject().put("name",parameters.getString("name")).toString())
            ServerManagementRequest.Action.DISABLE_CLIENT->Request("POST","/api/user/block",JSONObject().put("user_id",parameters.getString("client_id")).toString())
            else->error("Действие ещё не реализовано для этой версии SWG")
        }
    }
}
