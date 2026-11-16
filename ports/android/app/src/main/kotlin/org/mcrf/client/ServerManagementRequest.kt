package org.mcrf.client

import org.json.JSONObject
import java.util.UUID

/** Provider-neutral admission only. No recipe implies support for a WDTT-specific admin command. */
object ServerManagementRequest {
    enum class Action(val id:String,val title:String,val confirmation:Boolean) {
        STATUS("status","Состояние и трафик",false),
        CLIENTS("clients","Клиенты сервера",false),
        INTERFACES("interfaces","Интерфейсы для перезапуска",false),
        ADD_CLIENT("add-client","Добавить клиента",true),
        DISABLE_CLIENT("disable-client","Отключить клиента",true),
        CLIENT_EXPIRY("client-expiry","Изменить срок действия",true),
        CLIENT_LINK("client-link","Ссылка и QR подключения",false),
        SERVER_BACKUP("server-backup","Резервная копия сервера",true),
        RESTART("restart","Запросить перезапуск интерфейса",true)
    }
    /** Advertisements must come from an identity-checked provider adapter, not a profile link. */
    fun available(provider:String,advertised:Set<String>):List<Action> {
        require(provider in setOf("swg","3xui","wdtt")) {"Неизвестная серверная панель"}
        return Action.values().filter {it.id in advertised}
    }
    fun build(provider:String,action:Action,advertised:Set<String>,parameters:JSONObject=JSONObject(),confirmed:Boolean=false):JSONObject {
        require(action in available(provider,advertised)) {"Панель не подтвердила поддержку действия"}
        require(!action.confirmation||confirmed) {"Подтверди изменение сервера"}
        val allowed=when(action){
            Action.STATUS,Action.CLIENTS,Action.INTERFACES,Action.SERVER_BACKUP->emptySet()
            Action.RESTART->if(provider=="swg")setOf("node","iface")else emptySet()
            Action.ADD_CLIENT->setOf("name")
            Action.DISABLE_CLIENT,Action.CLIENT_LINK->setOf("client_id")
            Action.CLIENT_EXPIRY->setOf("client_id","expires_at")
        }
        require(parameters.keys().asSequence().toSet()==allowed) {"Некорректные параметры управления"}
        if("node" in allowed){
            require((parameters.opt("node") as? String)?.matches(Regex("[A-Za-z0-9_-]{1,128}"))==true) {"Некорректный узел"}
            require((parameters.opt("iface") as? String)?.matches(Regex("[A-Za-z0-9_][A-Za-z0-9_.-]{0,14}"))==true) {"Некорректный интерфейс"}
        }
        if("client_id" in allowed){
            val id=parameters.opt("client_id") as? String
            require(id!=null&&id.matches(Regex("[A-Za-z0-9_-]{1,128}"))) {"Некорректный идентификатор клиента"}
        }
        if("name" in allowed){
            val name=parameters.opt("name") as? String
            require(name!=null&&name.isNotBlank()&&name.length<=96&&name.none {it.isISOControl()}) {"Укажи имя клиента до 96 символов"}
        }
        if("expires_at" in allowed){
            val value=parameters.opt("expires_at")
            require(value is Int||value is Long) {"Нужен срок действия в секундах UTC"}
            require((value as Number).toLong() in 0..253402300799L) {"Некорректный срок действия"}
        }
        return JSONObject().put("schema",1).put("request_id",UUID.randomUUID().toString())
            .put("provider",provider).put("action",action.id).put("parameters",JSONObject(parameters.toString()))
    }
}
