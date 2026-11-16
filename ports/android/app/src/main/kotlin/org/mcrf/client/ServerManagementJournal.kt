package org.mcrf.client

import org.json.JSONObject
import java.security.MessageDigest

/** Persist an intent before a mutation. Pending/uncertain operations are never resumed automatically. */
object ServerManagementJournal {
    const val SECTION="serverOperations"
    enum class Rejection { ALREADY_RECORDED, FULL }
    class AdmissionRejected(val reason:Rejection):IllegalArgumentException(when(reason){
        Rejection.ALREADY_RECORDED->"Запрос уже записан. Автоматически повторять его нельзя; проверь состояние клиентов."
        Rejection.FULL->"Журнал действий заполнен. Новое изменение не отправлено; сохранённые данные не удалены."
    })
    private val uuid=Regex("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}")
    private val digest=Regex("[a-f0-9]{64}")
    private fun hash(text:String)=MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it.toInt() and 255)}
    fun validate(log:JSONObject){
        require(log.length()<=256)
        log.keys().forEach {id->
            require(uuid.matches(id))
            val record=log.getJSONObject(id)
            require(record.keys().asSequence().toSet()==setOf("provider","action","endpoint_sha256","parameters_sha256","state","created_at","updated_at"))
            require(record.getString("provider") in setOf("swg","3xui","wdtt"))
            require(ServerManagementRequest.Action.values().any {it.id==record.getString("action")&&it.confirmation})
            require(digest.matches(record.getString("endpoint_sha256"))&&digest.matches(record.getString("parameters_sha256")))
            require(record.getString("state") in setOf("pending","confirmed","uncertain"))
            for(key in listOf("created_at","updated_at"))require(record.get(key) is Long||record.get(key) is Int)
            require(record.getLong("created_at")>=0&&record.getLong("updated_at")>=record.getLong("created_at"))
        }
    }
    fun begin(log:JSONObject,request:JSONObject,endpoint:String,now:Long):JSONObject {
        validate(log);require(now>=0&&endpoint.isNotBlank()&&endpoint.length<=2048)
        val id=request.getString("request_id");require(uuid.matches(id))
        if(log.has(id))throw AdmissionRejected(Rejection.ALREADY_RECORDED)
        if(log.length()>=256)throw AdmissionRejected(Rejection.FULL)
        val action=ServerManagementRequest.Action.values().single {it.id==request.getString("action")}
        require(action.confirmation)
        ServerManagementRequest.build(request.getString("provider"),action,setOf(action.id),request.getJSONObject("parameters"),confirmed=true)
        return JSONObject(log.toString()).put(id,JSONObject().put("provider",request.getString("provider")).put("action",action.id)
            .put("endpoint_sha256",hash(endpoint)).put("parameters_sha256",hash(request.getJSONObject("parameters").toString()))
            .put("state","pending").put("created_at",now).put("updated_at",now)).also(::validate)
    }
    /** Read-only copies for this exact SSH/panel identity; never mix another server's results. */
    fun recordsFor(log:JSONObject,endpoint:String):List<JSONObject> {
        validate(log)
        val expected=hash(endpoint)
        return log.keys().asSequence().map {log.getJSONObject(it)}
            .filter {it.getString("endpoint_sha256")==expected}
            .map {JSONObject(it.toString())}.sortedByDescending {it.getLong("created_at")}.toList()
    }
    fun finish(log:JSONObject,id:String,confirmed:Boolean,now:Long):JSONObject {
        validate(log)
        val result=JSONObject(log.toString());val record=result.getJSONObject(id)
        require(record.getString("state")=="pending"&&now>=record.getLong("created_at")) {"Итог действия уже записан"}
        record.put("state",if(confirmed)"confirmed"else"uncertain").put("updated_at",now)
        validate(result);return result
    }
}
