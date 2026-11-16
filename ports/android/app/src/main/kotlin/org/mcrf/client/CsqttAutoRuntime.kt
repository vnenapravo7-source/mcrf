package org.mcrf.client

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import java.util.function.BooleanSupplier

/** Cache contains hashed profile keys and target IDs only. Never holds the UI thread. */
internal object CsqttAutoRuntime {
    // Refused admission stays deferred for a later import/connect; never retain
    // an unbounded queue of credential-bearing per-profile closures.
    private val worker=ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,ArrayBlockingQueue(16)) {work->
        Thread(work,"csqtt-generation-probe").apply {isDaemon=true}
    }
    private val scheduler=CsqttAutoDetection.Scheduler()
    private val detection=ReentrantLock()
    private fun cacheFile(context:Context,key:String)=File(File(context.filesDir,"csqtt-generation-cache"),key)
    fun cached(context:Context,profile:JSONObject):CsqttAutoDetection.Target? {
        val key=CsqttAutoDetection.key(profile)
        val file=cacheFile(context,key)
        val value=if(file.isFile&&file.length() in 1..64)file.readText()
            else context.applicationContext.getSharedPreferences("csqtt-generations",Context.MODE_PRIVATE).getString(key,null)
        return CsqttAutoDetection.order.firstOrNull {it.token==value}
    }
    private fun remember(context:Context,key:String,target:CsqttAutoDetection.Target,cancelled:BooleanSupplier) {
        val file=cacheFile(context,key)
        val directory=checkNotNull(file.parentFile)
        check(directory.isDirectory||directory.mkdirs()||directory.isDirectory)
        // The VPN and the probe run in different processes. Publish complete
        // entries atomically so either process sees the latest determination.
        val staged=File.createTempFile("result-",".tmp",directory)
        try {
            staged.writeText(target.token)
            if(!cancelled.asBoolean&&!Thread.currentThread().isInterrupted)
                Files.move(staged.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
        } finally {staged.delete()}
    }
    fun <T> connect(context:Context,profile:JSONObject,cancelled:BooleanSupplier,attempt:(JSONObject)->T):T {
        check(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper()) {"Определение CSQTT должно выполняться в фоне."}
        val key=CsqttAutoDetection.key(profile)
        return CsqttAutoDetection.connect(profile,runCatching {cached(context,profile)}.getOrNull(),{cancelled.asBoolean},
            {target->attempt(CsqttAutoDetection.profile(profile,target).apply {if(profile.optBoolean("probe_only"))put("probe_only",true)})},
            {target->runCatching {remember(context,key,target,cancelled)};Unit})
    }
    fun resolveForRouter(context:Context,profile:JSONObject,cancelled:BooleanSupplier):CsqttAutoDetection.Target {
        check(BsNativeContract.allowsVersionDetection(profile))
        runCatching {cached(context,profile)}.getOrNull()?.let {return it}
        val lease=EngineUpdateGate.acquireUsage(context)?:throw java.io.IOException("Движок обновляется. Повтори подключение после установки.")
        lease.use {
            VkCredentialBackoffStore(context).requireConnectionAllowed(profile)
            ProbeCoordinator.awaitQuiescent(context)
            detection.lockInterruptibly()
            try {
                if(cancelled.asBoolean)throw java.io.IOException("Подключение отменено")
                runCatching {cached(context,profile)}.getOrNull()?.let {return it}
                val physical=checkNotNull(DirectAccessCheck.physical(context)){"Нет физической сети для определения версии CSQTT."}
                val request=JSONObject(profile.toString()).put("probe_only",true)
                val owner=NativeCarrier.startForBsRouter(context,request,physical,cancelled,15_000)
                try {
                    check(!cancelled.asBoolean&&DirectAccessCheck.physical(context)==physical){"Подключение отменено или физическая сеть изменилась."}
                    val ready=checkNotNull(owner.streamProfile()){"Не удалось определить версию CSQTT."}
                    val family=CsqttGenerations.family(ready).id
                    val implementation=if(family=="2.5")CsqttGenerations.implementation(ready)else ""
                    return CsqttAutoDetection.Target(family,implementation).also {
                        check(it in CsqttAutoDetection.plan(profile,null)){"Ответил неожиданный вариант CSQTT."}
                    }
                }finally{OptionalCoreRetirement.drain(owner)}
            }finally{detection.unlock()}
        }
    }
    fun schedule(context:Context,profiles:List<JSONObject>,possible:Boolean,cancelled:()->Boolean) {
        if(!possible)return
        val app=context.applicationContext
        profiles.filter(CsqttGenerations::automatic).forEach {raw->
            val profile=JSONObject(raw.toString())
            scheduler.schedule(CsqttAutoDetection.key(profile),worker,possible) probe@{
                fun stopped()=cancelled()||ConnectionPresentation.pending(McrfVpnService.status)||CsqttProviderCooldown.active()
                if(CsqttProviderCooldown.deadline(app)>System.currentTimeMillis()||stopped()||!detection.tryLock())return@probe null
                try {
                    runCatching {cached(app,profile)}.getOrNull()?.let {return@probe it}
                    if(stopped())return@probe null
                    val cancelledProbe=BooleanSupplier(::stopped)
                    val physical=if(BsNativeContract.routerTransport(app,profile,true))
                        DirectAccessCheck.physical(app)?:return@probe null else null
                    val owner=if(physical!=null){
                        val request=JSONObject(profile.toString()).put("probe_only",true)
                        NativeCarrier.startForBsRouter(app,request,physical,cancelledProbe,15_000)
                    }else NativeCarrier.startBackground(app,profile,cancelledProbe,15_000)
                    OptionalCoreRetirement.drain(owner)
                    if(cancelledProbe.asBoolean||physical!=null&&DirectAccessCheck.physical(app)!=physical)null
                    else cached(app,profile)
                }finally{detection.unlock()}
            }
        }
    }
}
