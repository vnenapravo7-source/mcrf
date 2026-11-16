package org.mcrf.client

import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.Executor

/** Pure state and scheduling model. Only a typed protocol rejection advances the plan. */
internal object CsqttAutoDetection {
    data class Target(val generation:String,val implementation:String="") {
        val engine get()=CsqttGenerations.families.single {it.id==generation}.engine
        val token get()=generation+"/"+implementation
    }
    val order=listOf(Target("2.5","swg-panel-2.5.3"),Target("2.5","swg-panel-2.5.2"),Target("2.5","swg-panel-2.5.1"),Target("legacy"),Target("2.1.5"),Target("2.0"))
    fun negotiates(profile:JSONObject)=CsqttGenerations.automatic(profile)||(Csqtt25Profile.isProfile(profile)&&CsqttGenerations.implementation(profile)=="compatible-2.5")
    enum class Phase { DEFERRED, QUEUED, PROBING, RESOLVED }
    class State {
        @Volatile var phase=Phase.DEFERRED; private set
        @Volatile var target:Target?=null; private set
        @Synchronized fun schedule(executor:Executor,possible:Boolean,probe:()->Target?) {
            if(!possible||phase==Phase.QUEUED||phase==Phase.PROBING||phase==Phase.RESOLVED)return
            phase=Phase.QUEUED
            try {executor.execute {
                phase=Phase.PROBING
                try {target=probe()}catch(_:Exception){target=null}
                finally {phase=if(target==null)Phase.DEFERRED else Phase.RESOLVED}
            }}catch(_:RuntimeException){phase=Phase.DEFERRED}
        }
    }
    /** Share admission across callers; release failed/deferred work so it can be retried. */
    class Scheduler {
        private val pending=java.util.concurrent.ConcurrentHashMap<String,State>()
        fun schedule(key:String,executor:Executor,possible:Boolean,probe:()->Target?) {
            if(!possible)return
            val state=State()
            if(pending.putIfAbsent(key,state)!=null)return
            state.schedule(executor,true) {
                try {probe()}finally {pending.remove(key,state)}
            }
            if(state.phase==Phase.DEFERRED)pending.remove(key,state) // Executor refused admission.
        }
    }
    fun plan(profile:JSONObject,cached:Target?):List<Target> {
        if(!negotiates(profile))return listOf(Target(CsqttGenerations.family(profile).id,if(Csqtt25Profile.isProfile(profile))CsqttGenerations.implementation(profile)else""))
        val candidates=if(CsqttGenerations.automatic(profile))order else order.take(3)
        return if(cached in candidates)listOf(cached!!)+candidates.filter {it!=cached} else candidates
    }
    fun profile(source:JSONObject,target:Target)=CsqttGenerations.select(source,target.generation).apply {
        remove(CsqttGenerations.SELECTION);remove(CsqttGenerations.EPOCH)
        if(target.generation=="2.5")put("csqtt_implementation",target.implementation)
    }
    fun <T> connect(profile:JSONObject,cached:Target?,cancelled:()->Boolean,attempt:(Target)->T,remember:(Target)->Unit):T {
        val targets=plan(profile,cached)
        for((index,target)in targets.withIndex()) {
            if(cancelled()||Thread.currentThread().isInterrupted)throw IOException("Подключение отменено")
            val result=try {attempt(target)}catch(error:CsqttNativeFailure.Rejected) {
                if(!negotiates(profile)||error.kind!=CsqttNativeFailure.Kind.PROTOCOL||index==targets.lastIndex||cancelled()||Thread.currentThread().isInterrupted)throw error
                continue
            }
            try {
                if(cancelled()||Thread.currentThread().isInterrupted)throw IOException("Подключение отменено")
                if(negotiates(profile))remember(target)
                if(cancelled()||Thread.currentThread().isInterrupted)throw IOException("Подключение отменено")
            }catch(error:Throwable){
                (result as? AutoCloseable)?.let {OptionalCoreRetirement.fail(error,it)}
                throw error
            }
            return result
        }
        error("Нет поколений CSQTT")
    }
    /** Hash credentials rather than storing them in cache keys; edits/reset get a new key. */
    fun key(profile:JSONObject):String {
        val stable=JSONObject()
        profile.keys().asSequence().filter {it !in setOf("name","outbound_tag","probe_only")}.sorted().forEach {stable.put(it,profile.get(it))}
        return MessageDigest.getInstance("SHA-256").digest(stable.toString().toByteArray()).joinToString(""){"%02x".format(it)}
    }
    fun reset(profile:JSONObject)=CsqttGenerations.select(profile,CsqttGenerations.AUTO).put(CsqttGenerations.EPOCH,java.util.UUID.randomUUID().toString().replace("-",""))
}
