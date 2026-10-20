package org.mcrf.client

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.util.UUID

data class EngineUpdateScriptRecord(val id:String,val file:String,val version:String,val wire:Int,val api:Int,val key:String,val official:Boolean) {
    init {
        require(id.isNotBlank()&&id.length<=128&&id.none(Char::isISOControl))
        require(file.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}\\.flux")))
        require(version.isNotBlank()&&version.length<=256&&version.none(Char::isISOControl)&&wire>0&&api==1&&key.matches(Regex("[0-9a-f]{64}")))
    }
    fun json()=JSONObject().put("id",id).put("file",file).put("version",version).put("wire",wire).put("api",api).put("pubkey",key).put("official",official)
    companion object {
        fun fromJson(value:JSONObject):EngineUpdateScriptRecord {
            require(value.keys().asSequence().toSet()==setOf("id","file","version","wire","api","pubkey","official"))
            return EngineUpdateScriptRecord(value.getString("id"),value.getString("file"),value.getString("version"),value.getInt("wire"),value.getInt("api"),value.getString("pubkey"),value.getBoolean("official"))
        }
        fun inspected(value:JSONObject)=fromJson(JSONObject(value.toString()).apply {require(getString("status")=="verified");remove("status")})
    }
}

/** Device-local pinned-author registry. JSON alone never authorizes a package: inspect runs against real bytes. */
class EngineUpdateScriptCatalogue(source:String="") {
    private val records=linkedMapOf<String,EngineUpdateScriptRecord>()
    init {
        if(source.isNotEmpty()){
            require(source.toByteArray(Charsets.UTF_8).size<=65536)
            val data=JSONObject(source);require(data.keys().asSequence().toSet()==setOf("version","entries")&&data.getInt("version")==1)
            val entries=data.getJSONArray("entries");require(entries.length()<=32)
            repeat(entries.length()){i->val record=EngineUpdateScriptRecord.fromJson(entries.getJSONObject(i));require(record.id !in records&&records.values.none{it.file==record.file});records[record.id]=record}
        }
    }
    fun entries()=records.values.toList()
    fun get(id:String)=requireNotNull(records[id]){"Подписанный транспорт не зарегистрирован."}
    fun addVerified(record:EngineUpdateScriptRecord,explicitTrust:Boolean) {
        require(record.official||explicitTrust){"Для стороннего автора требуется отдельное подтверждение доверия к ключу."}
        require(records.size<32&&record.id !in records&&records.values.none{it.file==record.file}){"Транспорт уже зарегистрирован или реестр заполнен."}
        records[record.id]=record
    }
    fun replaceVerified(previous:EngineUpdateScriptRecord,current:EngineUpdateScriptRecord,allowWireBreak:Boolean) {
        require(get(previous.id)==previous&&current.id==previous.id&&current.file==previous.file){"Изменилась идентичность подписанного транспорта."}
        require(current.key==previous.key||previous.official&&current.official){"Сторонний ключ изменился; нужно новое решение о доверии."}
        require(current.wire==previous.wire||allowWireBreak){"Изменился wire-протокол; требуется явное подтверждение."}
        records[previous.id]=current
    }
    fun serialize()=JSONObject().put("version",1).put("entries",JSONArray(entries().map{it.json()})).toString().also{require(it.toByteArray(Charsets.UTF_8).size<=65536)}
}

/** Staged only: no production module enrollment, no script execution, no profile/catalogue writes. */
object EngineUpdateScriptRegistry {
    private fun read(context:Context):EngineUpdateScriptCatalogue {
        val file=AtomicFile(File(EngineUpdateScripts.persistentDirectory(context),"registry.json"))
        if(!file.baseFile.exists()&&!File(file.baseFile.path+".bak").exists())return EngineUpdateScriptCatalogue()
        val bytes=file.openRead().use{input->val output=ByteArrayOutputStream();val buffer=ByteArray(8192)
            while(true){val n=input.read(buffer);if(n<0)break;require(output.size()+n<=65536){"Реестр подписанных транспортов слишком большой."};output.write(buffer,0,n)};output.toByteArray()}
        return EngineUpdateScriptCatalogue(String(bytes,Charsets.UTF_8))
    }
    private fun save(context:Context,registry:EngineUpdateScriptCatalogue) {
        val file=AtomicFile(File(EngineUpdateScripts.persistentDirectory(context),"registry.json"));val out=file.startWrite()
        try{out.write(registry.serialize().toByteArray(Charsets.UTF_8));file.finishWrite(out)}catch(e:Exception){file.failWrite(out);throw e}
    }
    @Synchronized fun entries(context:Context)=read(context).entries()
    /** Caller provides author key and explicit third-party trust; native inspection verifies Ed25519 before adoption. */
    @Synchronized fun enrollCandidate(context:Context,input:InputStream,authorKey:String,explicitTrust:Boolean,
                                      active:()->Boolean={true},cancelled:()->Boolean={false}):EngineUpdateScriptRecord {
        require(!active()){"Останови соединение и замер перед добавлением транспорта."}
        require(authorKey.matches(Regex("[0-9a-f]{64}"))){"Нужен полный Ed25519 ключ автора."}
        val capabilities=EngineUpdateScripts.callCandidate(context,"capabilities",active=active,cancelled=cancelled)
        require(capabilities.getJSONArray("actions").let{list->(0 until list.length()).any{list.getString(it)=="inspect"}}){"Candidate не поддерживает проверку подписанного импорта."}
        val directory=EngineUpdateScripts.persistentDirectory(context)
        val file=File(directory,"transport-${UUID.randomUUID()}.flux");val temp=File(directory,".${file.name}.part")
        require(temp.createNewFile())
        var adopted=false
        try{
            FileOutputStream(temp).use{out->val buffer=ByteArray(8192);var bytes=0
                while(true){check(!cancelled()&&!active());val n=input.read(buffer);if(n<0)break;require(bytes+n<=4*1024*1024){"Пакет .flux превышает 4 МБ."};out.write(buffer,0,n);bytes+=n};require(bytes>0);out.fd.sync()
            }
            require(temp.renameTo(file))
            val inspected=inspect(context,file.name,authorKey,active,cancelled)
            val registry=read(context);registry.addVerified(inspected,explicitTrust);save(context,registry);adopted=true;return inspected
        }finally{temp.delete();if(!adopted)file.delete()}
    }
    private fun inspect(context:Context,file:String,key:String,active:()->Boolean,cancelled:()->Boolean)=
        EngineUpdateScriptRecord.inspected(EngineUpdateScripts.callCandidate(context,"inspect",file,key,active,cancelled))
    /** Commit registry/key rotation only after native installed report and a fresh signed-file inspection. */
    @Synchronized fun updateCandidate(context:Context,id:String,action:String,automatic:Boolean=false,consent:Boolean=false,
                                      allowWireBreak:Boolean=false,active:()->Boolean={true},cancelled:()->Boolean={false}):JSONObject {
        require(action in setOf("check","apply","rollback","recover"))
        val registry=read(context);var previous=registry.get(id)
        // A prior native commit can outlive an interrupted registry write. Re-inspect real bytes;
        // only pinned/compiled official keys and explicitly accepted wire changes may refresh it.
        if(File(EngineUpdateScripts.persistentDirectory(context),previous.file).isFile){
            val current=inspect(context,previous.file,previous.key,active,cancelled)
            if(current!=previous){registry.replaceVerified(previous,current,!automatic&&consent&&allowWireBreak);save(context,registry);previous=current}
        }
        val report=EngineUpdateScripts.callCandidate(context,action,previous.file,previous.key,active,cancelled,automatic,consent,allowWireBreak,previous.id,previous.wire)
        if(report.getString("status")=="installed"){
            val key=report.optString("newKey").ifEmpty{previous.key}
            val current=inspect(context,previous.file,key,active,cancelled)
            require(report.optString("latest").isEmpty()||report.getString("latest")==current.version){"Версия установленного .flux не совпала с квитанцией."}
            registry.replaceVerified(previous,current,!automatic&&consent&&allowWireBreak);save(context,registry)
        }
        if(action=="recover"&&report.getString("status") in setOf("installed","up_to_date")){
            require(reconcilePendingCandidate(context,!automatic&&consent&&allowWireBreak,active,cancelled)){
                "Восстановленный транспорт ещё не подтверждён общей квитанцией."}
        }
        return report
    }
    /** After process death, inspection must acquire the native script lock and verify every retained package.
     * A busy native child/missing package/changed unapproved wire keeps the gate closed; no timeout deletion. */
    @Synchronized fun reconcilePendingCandidate(context:Context,consentWireBreak:Boolean=false,active:()->Boolean={true},cancelled:()->Boolean={false}):Boolean {
        require(!active())
        val pending=EngineUpdateGate.pending(context)?:return true
        require(pending.action=="flux"&&pending.owner=="flux_candidate")
        return EngineUpdateGate.complete(context,pending.token){
            val registry=read(context)
            require(registry.entries().isNotEmpty()){"Нет зарегистрированного транспорта для проверенной квитанции."}
            registry.entries().forEach {previous->val current=inspect(context,previous.file,previous.key,active,cancelled);registry.replaceVerified(previous,current,consentWireBreak)}
            save(context,registry);true
        }
    }
}
