package org.mcrf.client

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import org.json.JSONObject

internal data class BsScreenOffSettings(val mode:String="balanced") {
    fun json()=JSONObject().put("mode",mode)
    fun holdCpu(screenOn:Boolean,sessionAlive:Boolean)=mode=="stable"&&!screenOn&&sessionAlive
    companion object {
        const val KEY="bsScreenOffOptions"
        fun fromJson(value:JSONObject):BsScreenOffSettings {
            require(value.keys().asSequence().all {it=="mode"}&&(!value.has("mode")||value.get("mode") is String))
            return BsScreenOffSettings(value.optString("mode","balanced")).also {require(it.mode in setOf("balanced","stable")) {"Неизвестный режим работы при выключенном экране."}}
        }
        fun load(context:Context)=synchronized(ProfileStore.storageLock) {
            val source=ProfileStore(context,"preferences.enc").read()
            val root=if(source.isBlank())JSONObject()else JSONObject(source)
            root.optJSONObject(KEY)?.let(::fromJson)?:BsScreenOffSettings()
        }
        fun save(context:Context,value:BsScreenOffSettings)=synchronized(ProfileStore.storageLock) {
            fromJson(value.json())
            val store=ProfileStore(context,"preferences.enc");val source=store.read()
            val root=if(source.isBlank())JSONObject()else JSONObject(source)
            root.put(KEY,value.json());store.write(root.toString())
        }
    }
}

/** A bounded CPU lease for one exact connected BS session, never a screen lock. */
internal class BsScreenOffLease(private val context:Context,private val settings:BsScreenOffSettings,private val alive:()->Boolean):AutoCloseable {
    private val handler=Handler(Looper.getMainLooper())
    private val power=context.getSystemService(PowerManager::class.java)
    private val lock=power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"MCRF:BS-screen-off").apply {setReferenceCounted(false)}
    private var closed=false
    private var registered=false
    private var reported=false
    private val refresh=object:Runnable {override fun run()=synchronized(this@BsScreenOffLease) {
        if(closed)return@synchronized
        handler.removeCallbacks(this)
        try {
            if(settings.holdCpu(power.isInteractive,alive())) {
                lock.acquire(10*60_000L)
                handler.postDelayed(this,5*60_000L)
            }else if(lock.isHeld)lock.release()
        }catch(_:Exception){
            if(!reported){reported=true;ClientEvents.record(context,"БС","Android не предоставил удержание CPU. Используется обычная фоновая работа.")}
        }
    }}
    private val receiver=object:BroadcastReceiver(){override fun onReceive(context:Context,intent:Intent){handler.post(refresh)}}
    fun start()=synchronized(this) {
        check(!closed&&!registered)
        val filter=IntentFilter(Intent.ACTION_SCREEN_ON).apply {addAction(Intent.ACTION_SCREEN_OFF)}
        if(Build.VERSION.SDK_INT>=33)context.registerReceiver(receiver,filter,Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") context.registerReceiver(receiver,filter)
        registered=true;handler.post(refresh)
    }
    override fun close()=synchronized(this) {
        if(closed)return@synchronized
        closed=true;handler.removeCallbacks(refresh)
        if(registered){runCatching {context.unregisterReceiver(receiver)};registered=false}
        if(lock.isHeld)runCatching {lock.release()}
        Unit
    }
}
