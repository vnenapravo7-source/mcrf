package org.mcrf.client

import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** UI observations only. Fresh signed-module verification belongs to the service worker. */
internal object CsqttDirectTunSupport {
    private data class Observation(val until:Long,val reason:String?)
    private val observations=ConcurrentHashMap<String,Observation>()
    private val pending=ConcurrentHashMap.newKeySet<String>()
    private val packageRevision=AtomicLong()
    @Volatile private var observingPackage=false
    private val worker=Executors.newSingleThreadExecutor {task->Thread(task,"mcrf-direct-tun-support").apply {isDaemon=true}}
    private const val CHECKING="Проверяем поддержку прямого TUN в установленном BS Engine…"
    private const val OBSERVATION_TTL_MS=24*60*60*1000L

    /** Only package events invalidate the observation promptly; no package polling or ELF hashing. */
    @Synchronized private fun observePackage(context:Context) {
        if(observingPackage)return
        val receiver=object:BroadcastReceiver() {
            override fun onReceive(context:Context?,intent:Intent?) {
                if(intent?.data?.schemeSpecificPart!=BsEngineContract.PACKAGE)return
                packageRevision.incrementAndGet()
                observations.replaceAll {_,value->value.copy(until=0)} // Keep the row stable during refresh.
            }
        }
        val filter=IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED);addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_REMOVED);addDataScheme("package")
        }
        if(android.os.Build.VERSION.SDK_INT>=33)context.registerReceiver(receiver,filter,Context.RECEIVER_NOT_EXPORTED)
        else context.registerReceiver(receiver,filter)
        observingPackage=true // Application-scoped receiver lives only as long as this process.
    }

    internal fun profileReason(profile:JSONObject?,vpn:Boolean):String?=when {
        !vpn->"Прямой TUN работает только в режиме VPN."
        profile==null||!Csqtt25Profile.isProfile(profile)->"Пока поддерживается только CSQTT 2.5.1–2.5.3 из BS Engine."
        CsqttGenerations.automatic(profile)->"Для теста выберите поколение CSQTT явно, без автоопределения."
        profile.optString("csqtt_implementation") !in setOf("swg-panel-2.5.1","swg-panel-2.5.2","swg-panel-2.5.3")->"Эта версия CSQTT не поддерживает проверенный прямой TUN."
        profile.optString("vk_hash_mode","manual")!="manual"||profile.optString("vk_token").isNotEmpty()->"Тест прямого TUN пока требует ручные хеши VK."
        else->null
    }

    /** Never performs package/archive/native IO or starts a process on the caller/UI thread. */
    fun unavailableReason(context:Context,profile:JSONObject?,vpn:Boolean):String? {
        profileReason(profile,vpn)?.let {return it}
        val application=context.applicationContext
        observePackage(application)
        val supplied=checkNotNull(profile)
        val key=supplied.optString("csqtt_implementation")
        val now=SystemClock.elapsedRealtime()
        val cached=observations[key]
        if(cached!=null&&cached.until>now)return cached.reason
        if(pending.add(key)) {
            val revision=packageRevision.get()
            val detached=JSONObject().put("type","csqtt").put("csqtt_generation","2.5").put("csqtt_implementation",key)
            worker.execute {
                val reason=try {BsDirectTunContract.resolve(application,detached);null}
                    catch(_:Exception){"Нужен подписанный BS Engine с capability прямого TUN-FD версии 1."}
                observations[key]=Observation(if(packageRevision.get()==revision)SystemClock.elapsedRealtime()+OBSERVATION_TTL_MS else 0,reason)
                pending.remove(key)
            }
        }
        return if(cached!=null)cached.reason else CHECKING
    }
}
