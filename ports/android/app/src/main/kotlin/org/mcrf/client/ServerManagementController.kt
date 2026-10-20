package org.mcrf.client

import android.content.Context
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Shared remote lease; mutations have an encrypted intent journal and no automatic retry. */
object ServerManagementController {
    private val worker=Executors.newSingleThreadExecutor()
    @Volatile private var activeId:String?=null
    @Volatile private var thread:Thread?=null
    private var cancellation=AtomicBoolean(false)
    fun read(context:Context,store:AndroidDeployStore,target:AndroidDeployTarget,saved:JSONObject,action:ServerManagementRequest.Action,complete:(JSONObject?,String?)->Unit):String? {
        require(action in setOf(ServerManagementRequest.Action.STATUS,ServerManagementRequest.Action.CLIENTS,ServerManagementRequest.Action.INTERFACES)) {"Нужен запрос чтения"}
        return begin(context,store,target,saved,action,JSONObject(),false,complete)
    }
    fun mutate(context:Context,store:AndroidDeployStore,target:AndroidDeployTarget,saved:JSONObject,action:ServerManagementRequest.Action,parameters:JSONObject,confirmed:Boolean,complete:(JSONObject?,String?)->Unit):String? {
        require(action in setOf(ServerManagementRequest.Action.ADD_CLIENT,ServerManagementRequest.Action.DISABLE_CLIENT,ServerManagementRequest.Action.RESTART)&&confirmed)
        return begin(context,store,target,saved,action,parameters,true,complete)
    }
    @Synchronized private fun begin(context:Context,store:AndroidDeployStore,target:AndroidDeployTarget,saved:JSONObject,action:ServerManagementRequest.Action,parameters:JSONObject,confirmed:Boolean,complete:(JSONObject?,String?)->Unit):String? {
        val request=ServerManagementRequest.build("swg",action,SwgServerManagementPlan.implementedActions,parameters,confirmed)
        val id=request.getString("request_id")
        val input=target.snapshot();val record=JSONObject(saved.toString());val cancelled=AtomicBoolean(false)
        if(!RemoteServerOperationGate.acquire(id))return null
        activeId=id;cancellation=cancelled
        try {worker.submit {
            thread=Thread.currentThread()
            var result:JSONObject?=null;var failure:String?=null
            var journalStarted=false
            try {
                check(!cancelled.get())
                if(action.confirmation){
                    val endpoint="${input.user}@${input.host}:${input.sshPort}|${record.getString("panel_url")}|${record.getString("certificate_sha256")}"
                    store.update(ServerManagementJournal.SECTION){log->
                        val updated=ServerManagementJournal.begin(log,request,endpoint,System.currentTimeMillis())
                        log.put(id,updated.getJSONObject(id))
                    }
                    journalStarted=true
                }
                result=ServerManagementSsh(context.applicationContext,store).execute(input,record,request,confirmed){cancelled.get()}
                if(journalStarted)store.update(ServerManagementJournal.SECTION){log->log.put(id,ServerManagementJournal.finish(log,id,true,System.currentTimeMillis()).getJSONObject(id))}
            }catch(error:Exception){
                result=null
                if(journalStarted)runCatching {store.update(ServerManagementJournal.SECTION){log->log.put(id,ServerManagementJournal.finish(log,id,false,System.currentTimeMillis()).getJSONObject(id))}}
                failure=if(error is ServerManagementJournal.AdmissionRejected)error.message
                    else if(journalStarted)"Результат изменения не подтверждён. Сначала проверь состояние панели; запрос автоматически не повторяется."
                    else if(cancelled.get())"Запрос отменён"else"Не удалось подтвердить ответ сервера. Проверь SSH-ключ, вход и сертификат панели."
            }
            finally {
                synchronized(this){thread=null;activeId=null;RemoteServerOperationGate.release(id)}
                runCatching {complete(result,failure)}
            }
        }}catch(error:Exception){activeId=null;RemoteServerOperationGate.release(id);throw error}
        return id
    }
    @Synchronized fun cancel(id:String) {if(activeId==id){cancellation.set(true);thread?.interrupt()}}
}
