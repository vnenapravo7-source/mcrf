package org.mcrf.client

import android.content.Context
import android.system.Os
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.function.BooleanSupplier

/** Explicit connect preflight only; never called from health checks or NativeCarrier.start. */
internal object WdttMasqueRegistration {
    private val lock=Any()
    internal fun profiles(template:JSONObject,extra:List<JSONObject>):List<JSONObject> {
        val all=extra.toMutableList()
        template.optJSONObject("mcrf_carrier")?.let(all::add)
        template.optJSONObject("mcrf_bs")?.optJSONArray("candidates")?.let {a->repeat(a.length()){a.getJSONObject(it).optJSONObject("profile")?.let(all::add)}}
        return all.filter {it.optString("type")=="wdtt"&&it.optBoolean("rt_masque")}
    }
    fun prepare(context:Context,template:JSONObject,extra:List<JSONObject>,cancelled:BooleanSupplier)=synchronized(lock) {
        val requested=profiles(template,extra)
        if(requested.isEmpty())return@synchronized
        requested.forEach {SwgProfile.fromJson("masque-preflight",it)}
        NativeCarrier.requireOptions(context,requested.first())
        if(!WdttMasqueStore.accepted(context))throw WdttMasqueConsentRequired()
        if(WdttMasqueStore.load(context)!=null)return@synchronized
        check(!cancelled.asBoolean) {"Подготовка MASQUE отменена."}
        val binary=NativeCarrier.executable(context,"wdtt")
        val folder=File(context.cacheDir,"mcrf-masque-enroll-${UUID.randomUUID()}")
        check(folder.mkdir());Os.chmod(folder.absolutePath,0x1c0)
        var child:Process?=null;var retiring:ClientCarrierRetirement?=null;var reader:Thread?=null
        try {
            val config=File(folder,"enroll.json")
            check(config.createNewFile());Os.chmod(config.absolutePath,0x180)
            config.outputStream().use {it.write(JSONObject().put("turn_sni",requested.first().getString("turn_sni")).put("accept_tos",true).toString().toByteArray(Charsets.UTF_8))}
            val process=ProcessBuilder(binary.absolutePath,"--mcrf-enroll-masque",config.absolutePath).directory(folder).redirectErrorStream(true).start()
            child=process;retiring=ClientCarrierRetirement(process,3)
            reader=Thread({try{process.inputStream.use {input->val buffer=ByteArray(4096);try{while(input.read(buffer)>=0){}}finally{buffer.fill(0)}}}catch(_:Exception){}},"mcrf-masque-enrollment-output").apply {start()}
            val deadline=android.os.SystemClock.elapsedRealtime()+45_000
            while(!process.waitFor(100,TimeUnit.MILLISECONDS))check(!cancelled.asBoolean&&WdttMasqueStore.accepted(context)&&android.os.SystemClock.elapsedRealtime()<deadline) {"Подготовка MASQUE остановлена или истёк срок ожидания."}
            check(!cancelled.asBoolean&&WdttMasqueStore.accepted(context)) {"Подготовка MASQUE отменена."}
            check(process.exitValue()==0) {"Не удалось подготовить учётную запись MASQUE. Повторите подключение позже."}
            val account=File(folder,"masque-account.json")
            check(!java.nio.file.Files.isSymbolicLink(account.toPath())&&account.isFile&&account.length() in 1L..32768L)
            WdttMasqueStore.save(context,JSONObject(account.readText(Charsets.UTF_8)))
            ClientEvents.record(context,"БС","Учётная запись MASQUE подготовлена и сохранена зашифрованно.")
        }finally {
            val owned=child
            if(retiring!=null)OptionalCoreRetirement.drain(retiring)
            else if(owned!=null)OptionalCoreRetirement.drain(AutoCloseable {OptionalCoreTermination.release(owned,false)})
            var interrupted=Thread.interrupted()
            try{reader?.join(1000)}catch(_:InterruptedException){interrupted=true}
            runCatching {child?.inputStream?.close()};runCatching {child?.errorStream?.close()};runCatching {child?.outputStream?.close()}
            while(reader?.isAlive==true)try{reader.join(100)}catch(_:InterruptedException){interrupted=true}
            // Exact fresh private directory only, after its child has really died.
            if(child?.isAlive!=true) {
                folder.listFiles()?.forEach {file->if(file.isFile&&!java.nio.file.Files.isSymbolicLink(file.toPath()))file.delete()}
                folder.delete()
            }
            if(interrupted)Thread.currentThread().interrupt()
        }
    }
}
