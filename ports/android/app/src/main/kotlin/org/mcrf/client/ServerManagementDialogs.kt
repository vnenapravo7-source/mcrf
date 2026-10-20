package org.mcrf.client

import android.app.Activity
import android.app.AlertDialog
import org.json.JSONObject

/** No IO. The callback is reachable only through the explicit positive confirmation. */
object ServerManagementDialogs {
    fun confirm(activity:Activity,host:String,label:String,action:ServerManagementRequest.Action,parameters:JSONObject,proceed:(JSONObject)->Unit):AlertDialog {
        require(host.isNotBlank()&&host.none {it.isISOControl()})
        require(action in setOf(ServerManagementRequest.Action.ADD_CLIENT,ServerManagementRequest.Action.DISABLE_CLIENT,ServerManagementRequest.Action.CLIENT_EXPIRY,ServerManagementRequest.Action.RESTART))
        val captured=ServerManagementRequest.build("swg",action,SwgServerManagementPlan.implementedActions,parameters,true).getJSONObject("parameters")
        val (title,description,button)=when(action){
            ServerManagementRequest.Action.ADD_CLIENT->Triple("Создать клиента?","Будет создана запись клиента. Панель может назначить звонки из своего пула; ключи и подключения настраиваются отдельно.","Создать")
            ServerManagementRequest.Action.DISABLE_CLIENT->Triple("Отключить клиента?","Доступ будет отключён, запись клиента сохранится.","Отключить")
            ServerManagementRequest.Action.RESTART->Triple("Поставить перезапуск в очередь?","Только интерфейс ${captured.getString("iface")} узла ${captured.getString("node")}. Соединения через него временно оборвутся. Панель передаст запрос при очередной синхронизации; ответ о приёме не подтверждает фактический перезапуск.","В очередь")
            else->if(captured.getLong("expires_at")==0L)Triple("Убрать срок действия?","Клиент сможет подключаться без ограничения по дате.","Убрать срок")
                else Triple("Изменить срок?","Доступ до ${java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM,java.text.DateFormat.SHORT).format(java.util.Date(captured.getLong("expires_at")*1000))} по времени телефона.","Изменить")
        }
        var submitted=false
        return ThemedDialogBuilder(activity).setTitle(title).setMessage("$label · $host\n$description")
            .setNegativeButton("Отмена",null).setPositiveButton(button){_,_->
                if(!submitted){submitted=true;proceed(JSONObject(captured.toString()))}
            }.create().apply {show()}
    }
}
