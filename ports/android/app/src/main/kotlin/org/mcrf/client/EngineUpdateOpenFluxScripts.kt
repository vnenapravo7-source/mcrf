package org.mcrf.client

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Production signed-package caller. No candidate enrollment, trust prompts or native downloads. */
internal object EngineUpdateOpenFluxScripts {
    private val worker=Executors.newSingleThreadExecutor()
    private val busy=AtomicBoolean(false)
    private val lock=Any()
    data class Snapshot(val message:String="Подписанные JS-транспорты: сначала проверьте состояние модуля.",
                        val entries:List<EngineUpdateOpenFluxScriptPolicy.Receipt> = emptyList(),
                        val candidates:Map<String,EngineUpdateOpenFluxScriptPolicy.Receipt> = emptyMap(),val recoveryRequired:Boolean=false,val verified:Boolean=false)
    @Volatile private var state=Snapshot()
    fun snapshot()=state
    fun isBusy()=busy.get()
    private fun atomic(context:Context,name:String):AtomicFile {
        val root=File(context.noBackupFilesDir.canonicalFile,"engine-update-openflux-scripts")
        require(root.isDirectory||root.mkdir());require(root.canonicalFile==root.absoluteFile&&!java.nio.file.Files.isSymbolicLink(root.toPath()))
        android.system.Os.chmod(root.path,0x1c0)
        val file=File(root,name)
        for(suffix in listOf("",".bak",".new"))require(!java.nio.file.Files.isSymbolicLink(File(file.path+suffix).toPath()))
        return AtomicFile(file)
    }
    private fun read(context:Context,name:String):JSONObject? {
        val file=atomic(context,name)
        if(!file.baseFile.exists()&&!File(file.baseFile.path+".bak").exists())return null
        val bytes=file.openRead().use {input->val out=ByteArrayOutputStream();val buffer=ByteArray(4096)
            while(true){val n=input.read(buffer);if(n<0)break;require(out.size()+n<=32768);out.write(buffer,0,n)};out.toByteArray()}
        return JSONObject(String(bytes,Charsets.UTF_8))
    }
    private fun write(context:Context,name:String,data:JSONObject) {
        val bytes=data.toString().toByteArray(Charsets.UTF_8);require(bytes.size<=32768)
        val file=atomic(context,name);val out=file.startWrite()
        try {out.write(bytes);file.finishWrite(out);android.system.Os.chmod(file.baseFile.path,0x180)}catch(error:Exception){file.failWrite(out);throw error}
    }
    private fun loadCandidates(context:Context,host:EnginePackages.OpenFluxScriptHost):Map<String,EngineUpdateOpenFluxScriptPolicy.Receipt> {
        val saved=read(context,"candidates.json")?:return emptyMap()
        require(saved.getInt("version")==1)
        if(saved.getString("host")!=host.fingerprint)return emptyMap()
        val rows=saved.getJSONObject("entries");require(rows.length()<=EngineUpdateOpenFluxScriptPolicy.ids.size)
        return rows.keys().asSequence().associateWith {id->EngineUpdateOpenFluxScriptPolicy.selection(rows.getJSONObject(id)).also {require(it.id==id)}}
    }
    private fun saveCandidates(context:Context,host:EnginePackages.OpenFluxScriptHost,rows:Map<String,EngineUpdateOpenFluxScriptPolicy.Receipt>) {
        val entries=JSONObject();rows.forEach {(id,r)->entries.put(id,r.json())}
        write(context,"candidates.json",JSONObject().put("version",1).put("host",host.fingerprint).put("entries",entries))
    }
    private fun launch(context:Context,operation:(Context,EngineUpdateRun)->Unit):EngineUpdateRun? = synchronized(lock) {
        if(!busy.compareAndSet(false,true))return@synchronized null
        val attempt=EngineUpdateRun();val app=context.applicationContext
        try {worker.execute {
            var failed=false
            try {operation(app,attempt)}catch(_:Exception){
                failed=true
                val pending=runCatching{EngineUpdateGate.pending(app)?.owner==EngineUpdateOpenFluxScriptPolicy.OWNER}.getOrDefault(true)
                state=state.copy(message=if(attempt.cancelled)"Операция отменена. Проверьте состояние перед следующим действием." else "Проверка не завершена: нужен подписанный модуль пакета C и свободный движок. Повторите проверку состояния.",recoveryRequired=pending,verified=false)
            }finally {attempt.finish(failed);busy.set(false)}
        }}catch(error:Exception){busy.set(false);attempt.finish(true);throw error}
        attempt
    }
    private fun nativeCall(context:Context,host:EnginePackages.OpenFluxScriptHost,request:JSONObject,cancelled:()->Boolean):JSONObject {
        require(EnginePackages.openFluxScriptHost(context)==host){"Модуль изменился; повторите проверку."}
        val dir=File(context.cacheDir.canonicalFile,"mcrf-carrier-${UUID.randomUUID()}");require(dir.mkdir())
        val file=File(dir,"script-update.json");var child:Process?=null
        try {
            android.system.Os.chmod(dir.path,0x1c0);require(file.createNewFile());android.system.Os.chmod(file.path,0x180)
            java.io.FileOutputStream(file).use {it.write(request.toString().toByteArray(Charsets.UTF_8));it.fd.sync()}
            check(!cancelled())
            child=ProcessBuilder(host.binary.path,"--mcrf-script-config",file.path).directory(dir).redirectErrorStream(true).start()
            val process=child
            val output=FutureTask {
                process.inputStream.use {input->val out=ByteArrayOutputStream();val buffer=ByteArray(4096)
                    while(true){val n=input.read(buffer);if(n<0)break;require(out.size()+n<=65536);out.write(buffer,0,n)};out.toString("UTF-8")}
            }
            Thread(output,"mcrf-official-script-response").apply {isDaemon=true;start()}
            val deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(3)
            // Keep stdin open. Closing it is cancellation, never an installation receipt.
            while(!process.waitFor(200,TimeUnit.MILLISECONDS)) {
                check(!cancelled()&&System.nanoTime()<deadline)
                if(output.isDone)output.get()
            }
            require(process.exitValue()==0){"Подписанный модуль отклонил операцию."}
            val line=output.get(3,TimeUnit.SECONDS).lineSequence().singleOrNull {it.startsWith("MCRF_SCRIPT ")}
                ?:throw IllegalArgumentException("Нет подтверждённого ответа updater.")
            require(EnginePackages.openFluxScriptHost(context)==host){"Модуль изменился во время операции."}
            return JSONObject(line.removePrefix("MCRF_SCRIPT "))
        }finally {
            EngineScriptCleanup.finish(
                child=child?.let {process->AutoCloseable {runCatching{process.outputStream.close()};OptionalCoreTermination.release(process,graceful=false)}},
                complete={},release={},
                deleteRequest={check(!file.exists()||file.delete());check(!dir.exists()||dir.delete())},finished={},
            )
        }
    }
    private fun inventory(context:Context,host:EnginePackages.OpenFluxScriptHost,cancelled:()->Boolean)=
        EngineUpdateOpenFluxScriptPolicy.inventory(nativeCall(context,host,EngineUpdateOpenFluxScriptPolicy.request("status"),cancelled),host.abi)

    /** Existing global usage/mutation gate also fences APK install and every connection/probe start. */
    private fun refreshNow(context:Context,attempt:EngineUpdateRun):EnginePackages.OpenFluxScriptHost {
        val pending=EngineUpdateGate.pending(context)
        if(pending?.owner==EngineUpdateOpenFluxScriptPolicy.OWNER){recoverNow(context,pending);return EnginePackages.openFluxScriptHost(context)}
        require(pending==null){"Сначала завершите текущую установку движка."}
        val usage=EngineUpdateGate.acquireUsage(context)?:error("Движок занят изменением.")
        try {
            val host=EnginePackages.openFluxScriptHost(context)
            val entries=inventory(context,host){attempt.cancelled}
            // Cached choices are not trust anchors. Corrupt/stale choices are
            // discarded only after a fresh native inventory has been verified.
            val candidates=runCatching{loadCandidates(context,host)}.getOrDefault(emptyMap()).filter {(id,r)->entries.single {it.id==id}.sha==r.currentSha}
            state=Snapshot("Подписи и состояние проверены. Изменения применяются при следующем подключении.",entries,candidates,verified=true)
            return host
        }finally {usage.close()}
    }
    fun inspect(context:Context)=launch(context){app,attempt->refreshNow(app,attempt)}
    fun check(context:Context,id:String)=launch(context){app,attempt->
        require(id in EngineUpdateOpenFluxScriptPolicy.ids)
        val host=refreshNow(app,attempt)
        val usage=EngineUpdateGate.acquireUsage(app)?:error("Движок занят изменением.")
        try {
            val before=state.entries.single {it.id==id}
            val result=nativeCall(app,host,EngineUpdateOpenFluxScriptPolicy.request("check",id)){attempt.cancelled}
            val checked=EngineUpdateOpenFluxScriptPolicy.verifiedResult("check",id,"",before.sha,result,host.abi)
            val candidates=state.candidates+(id to checked);saveCandidates(app,host,candidates)
            state=state.copy(candidates=candidates,message=if(checked.sha==before.sha)"$id: уже установлена проверенная версия ${before.version}." else "$id: проверена версия ${checked.version}. Для применения отключите соединение и замер.")
        }finally {usage.close()}
    }
    fun mutate(context:Context,id:String,action:String,expectedSHA:String)=launch(context){app,attempt->
        require(action in setOf("apply","rollback"));EngineUpdateOpenFluxScriptPolicy.request(action,id,expectedSHA)
        val host=refreshNow(app,attempt)
        val before=state.entries.single {it.id==id}
        if(action=="apply")require(state.candidates[id]?.let {it.sha==expectedSHA&&it.currentSha==before.sha}==true){"Сначала проверьте точный пакет."}
        else require(before.previousSha==expectedSHA&&expectedSHA!=before.sha){"Нет подтверждённой предыдущей версии."}
        val lease=EngineUpdateGate.beginMutation(app,EngineUpdateOpenFluxScriptPolicy.OWNER,"flux","openflux")?:error("Отключите соединение и завершите замер.")
        var launched=false;var failure:Exception?=null
        try {
            write(app,"pending.json",JSONObject().put("version",1).put("token",lease.pending.token).put("id",id)
                .put("action",action).put("before",before.sha).put("expected",expectedSHA))
            state=state.copy(message=if(action=="rollback")"Проверяем и выбираем предыдущий пакет…"else"Применяем проверенный пакет…",recoveryRequired=true)
            check(!attempt.cancelled)
            launched=true
            val result=nativeCall(app,host,EngineUpdateOpenFluxScriptPolicy.request(action,id,expectedSHA)){attempt.cancelled}
            EngineUpdateOpenFluxScriptPolicy.verifiedResult(action,id,expectedSHA,before.sha,result,host.abi)
        }catch(error:Exception){failure=error}finally {
            // nativeCall retires its exact child before returning. A second offline
            // native receipt (and its kernel lease) resolves commit vs interrupted apply.
            try {
                if(!launched) {
                    check(EngineUpdateGate.complete(app,lease.pending.token){true})
                    atomic(app,"pending.json").delete()
                    state=state.copy(recoveryRequired=false)
                }else reconcile(app,lease.pending,host)
            }finally {lease.close()}
        }
        // Lost stdout or late cancellation is not a failed install when the
        // independent offline receipt confirms the exact requested selection.
        failure?.let {error->if(state.entries.singleOrNull {it.id==id}?.sha!=expectedSHA||expectedSHA==before.sha)throw error}
    }
    private fun reconcile(context:Context,pending:EngineUpdateGate.Pending,host:EnginePackages.OpenFluxScriptHost) {
        require(pending.owner==EngineUpdateOpenFluxScriptPolicy.OWNER&&pending.action=="flux"&&pending.kind=="openflux")
        val journal=read(context,"pending.json")
        require(journal==null||journal.getString("token")==pending.token){"Журнал относится к другой операции."}
        val entries=inventory(context,host){false}
        var changed=false
        if(journal!=null) {
            require(journal.getInt("version")==1)
            val id=journal.getString("id");val action=journal.getString("action");val expected=journal.getString("expected");val before=journal.getString("before")
            EngineUpdateOpenFluxScriptPolicy.request(action,id,expected)
            changed=EngineUpdateOpenFluxScriptPolicy.reconcile(entries.single {it.id==id},id,before,expected)
        }
        check(EngineUpdateGate.complete(context,pending.token){it.owner==EngineUpdateOpenFluxScriptPolicy.OWNER&&it.action=="flux"})
        atomic(context,"pending.json").delete()
        // An updated module or recovered commit invalidates prior candidate decisions.
        saveCandidates(context,host,emptyMap())
        state=Snapshot(if(changed)"Пакет выбран и повторно проверен. Подключитесь снова обычной кнопкой — новый процесс загрузит его. При проблеме доступен откат." else "Состояние повторно проверено; незавершённая операция закрыта. Текущий пакет сохранён.",entries,verified=true)
    }
    private fun recoverNow(context:Context,pending:EngineUpdateGate.Pending) {
        val lease=EngineUpdateGate.resumeMutation(context,pending.token)?:error("Предыдущий updater ещё работает.")
        try {reconcile(context,pending,EnginePackages.openFluxScriptHost(context))}finally{lease.close()}
    }
    /** Startup/UI recovery only inspects local signed bytes; it never fetches/applies/restarts. */
    fun recover(context:Context):EngineUpdateRun? {
        val pending=EngineUpdateGate.pending(context)?:return null
        if(pending.owner!=EngineUpdateOpenFluxScriptPolicy.OWNER)return null
        return launch(context){app,_->recoverNow(app,pending)}
    }
}
