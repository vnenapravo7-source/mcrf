package org.mcrf.client

import org.json.JSONObject

/** Drops private keys, subscription tokens, VK links and diagnostic bodies before presentation. */
object SwgServerManagementState {
    class Client(val id:String,val name:String,val disabled:Boolean,val expiresAt:Long) {
        override fun toString()="ManagedClient(redacted)"
    }
    fun clients(response:JSONObject):List<Client> {
        require(response.toString().toByteArray(Charsets.UTF_8).size<=4*1024*1024) {"Ответ панели слишком большой"}
        require(response.opt("ok")==true) {"Панель не подтвердила запрос"}
        val users=response.getJSONObject("data").getJSONObject("roster").getJSONObject("users")
        require(users.length()<=2048) {"Слишком много клиентов для одного ответа"}
        return users.keys().asSequence().map {id->
            require(id.matches(Regex("[A-Za-z0-9_-]{1,128}")))
            val user=users.getJSONObject(id)
            val name=user.opt("name") as? String?:error("Панель не вернула имя клиента")
            require(name.length in 1..96&&name.none {it.isISOControl()})
            require(!user.has("disabled")||user.opt("disabled") is Boolean)
            val expiry=if(user.has("expiry"))user.get("expiry")else 0L
            require(expiry is Int||expiry is Long)
            require((expiry as Number).toLong() in 0..253402300799L)
            Client(id,name,user.optBoolean("disabled"),expiry.toLong())
        }.sortedWith(compareBy<Client> {it.name.lowercase()}.thenBy {it.id}).toList()
    }
}
