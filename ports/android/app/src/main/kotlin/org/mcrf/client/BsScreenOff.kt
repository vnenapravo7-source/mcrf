package org.mcrf.client

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Unknown battery/charger values are not zero/disconnected. No sticky intent
 * or invalid scale must accidentally activate the optional battery policy. */
internal data class BsScreenOffBattery(val percent:Int?=null,val charging:Boolean?=null) {
    init {require(percent==null||percent in 0..100)}
    companion object {
        fun fromRaw(level:Int?,scale:Int?,plugged:Int?):BsScreenOffBattery {
            val percent=if(level!=null&&scale!=null&&scale>0&&level in 0..scale)
                (level.toLong()*100/scale).toInt() else null
            return BsScreenOffBattery(percent,plugged?.takeIf {it>=0}?.let {it>0})
        }
        fun fromIntent(intent:Intent?):BsScreenOffBattery {
            if(intent?.action!=Intent.ACTION_BATTERY_CHANGED)return BsScreenOffBattery()
            @Suppress("DEPRECATION")
            fun number(key:String)=intent.extras?.get(key) as? Int
            return try {fromRaw(number(BatteryManager.EXTRA_LEVEL),number(BatteryManager.EXTRA_SCALE),number(BatteryManager.EXTRA_PLUGGED))}
                catch(_:Exception){BsScreenOffBattery()}
        }
    }
}

internal data class BsScreenOffSettings(val mode:String="balanced",val batteryThresholdPercent:Int?=null,
    val batteryWhileCharging:Boolean=false) {
    init {
        require(mode in MODES) {"Неизвестный режим работы при выключенном экране."}
        require(batteryThresholdPercent==null||batteryThresholdPercent in 1..100) {"Порог батареи должен быть от 1 до 100%."}
        require(batteryThresholdPercent!=null||!batteryWhileCharging) {"Сначала включите порог батареи."}
    }
    // Absent opt-in serializes EXACTLY as the old mode-only section.
    fun json()=JSONObject().put("mode",mode).apply {
        batteryThresholdPercent?.let {put(THRESHOLD,it);put(CHARGING,batteryWhileCharging)}
    }
    /** Service eligibility only: AUTO must keep observing even while the system saver is on. */
    val needsLease:Boolean get()=mode=="stable"||mode=="auto"
    val watchesBattery:Boolean get()=mode=="auto"&&batteryThresholdPercent!=null
    fun batterySaving(battery:BsScreenOffBattery):Boolean = watchesBattery&&
        battery.percent?.let {it<=requireNotNull(batteryThresholdPercent)}==true&&
        (batteryWhileCharging||battery.charging==false)
    fun holdCpu(screenOn:Boolean,sessionAlive:Boolean,systemPowerSave:Boolean=false,battery:BsScreenOffBattery=BsScreenOffBattery())=
        sessionAlive&&!screenOn&&(mode=="stable"||mode=="auto"&&!systemPowerSave&&!batterySaving(battery))
    companion object {
        const val KEY="bsScreenOffOptions"
        const val THRESHOLD="batteryThresholdPercent"
        const val CHARGING="batteryWhileCharging"
        internal const val CHANGED="org.mcrf.client.BS_SCREEN_OFF_POLICY_CHANGED"
        const val DEFAULT_THRESHOLD=20 // Editor's opt-in suggestion, never a read-time default.
        val THRESHOLD_PRESETS=listOf(5,10,15,20,25,30,35,40,50,60,70,80,90,100)
        val MODES=listOf("balanced","stable","auto")
        fun fromJson(value:JSONObject):BsScreenOffSettings {
            require(value.keys().asSequence().all {it in setOf("mode",THRESHOLD,CHARGING)}&&(!value.has("mode")||value.get("mode") is String))
            require(!value.has(THRESHOLD)||value.get(THRESHOLD) is Int) {"Порог батареи должен быть целым числом от 1 до 100%."}
            require(!value.has(CHARGING)||(value.has(THRESHOLD)&&value.get(CHARGING) is Boolean))
            return BsScreenOffSettings(value.optString("mode","balanced"),
                if(value.has(THRESHOLD))value.getInt(THRESHOLD)else null,value.optBoolean(CHARGING,false))
        }
        // Upstream0188606a defaults to AUTO. Keep MCRF's absent/empty setting balanced;
        // AUTO is opt-in and reading preferences never migrates an existing choice.
        fun fromPreferences(root:JSONObject):BsScreenOffSettings {
            require(!root.has(KEY)||root.get(KEY) is JSONObject) {"Некорректные настройки работы при выключенном экране."}
            return if(root.has(KEY))fromJson(root.getJSONObject(KEY))else BsScreenOffSettings()
        }
        fun load(context:Context)=synchronized(ProfileStore.storageLock) {
            // ProfileStore.read can restore AtomicFile backups or create a
            // missing key. Observing battery/settings must do neither; pending
            // recovery fails closed until the normal store owner resolves it.
            readOnly(File(context.applicationInfo.dataDir,"files")) {
                val keys=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
                require(keys.containsAlias("mcrf-profile-v1")) {"Ключ настроек недоступен."}
                keys.getKey("mcrf-profile-v1",null) as? SecretKey?:error("Ключ настроек недоступен.")
            }
        }
        /** Same existing encrypted schema/generation, no mkdir, AtomicFile
         * recovery, key generation or default persistence. Key supplier is a
         * test seam and is never consulted for an absent preferences file. */
        internal fun readOnly(files:File,key:()->SecretKey):BsScreenOffSettings {
            fun stable(file:File) {require(!Files.isSymbolicLink(file.toPath())&&!File(file.path+".bak").exists()&&!File(file.path+".new").exists()) {"Настройки ещё сохраняются. Повторите позже."}}
            fun bytes(file:File,maximum:Int):ByteArray {
                stable(file);val size=file.length()
                require(file.isFile&&size in 1..maximum.toLong()) {"Повреждены настройки."}
                return java.io.DataInputStream(file.inputStream()).use {input->
                    ByteArray(size.toInt()).also {input.readFully(it);require(input.read()==-1);stable(file)}
                }
            }
            stable(files)
            val pointer=File(files,"active-profile.txt");stable(pointer)
            val pointerBytes=if(pointer.exists())bytes(pointer,128)else null
            val directory=if(pointerBytes!=null) {
                val name=String(pointerBytes,Charsets.US_ASCII)
                require(name.matches(Regex("mcrf-store-[0-9a-f-]{36}"))) {"Повреждён указатель настроек."}
                File(files,name).also {stable(it);require(it.isDirectory&&it.canonicalFile.parentFile==files.canonicalFile)}
            }else files
            val preferences=File(directory,"preferences.enc");stable(preferences)
            if(!preferences.exists())return BsScreenOffSettings()
            fun identity()=Files.readAttributes(preferences.toPath(),java.nio.file.attribute.BasicFileAttributes::class.java,
                java.nio.file.LinkOption.NOFOLLOW_LINKS).let {Triple(it.fileKey(),it.lastModifiedTime(),it.size())}
            val originalIdentity=identity()
            val encrypted=bytes(preferences,8*1024*1024)
            try {
                require(encrypted.size>=32&&java.nio.ByteBuffer.wrap(encrypted,0,4).int==0x4d435246) {"Повреждены настройки."}
                val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply {init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,encrypted.copyOfRange(4,16)))}
                val plain=cipher.doFinal(encrypted,16,encrypted.size-16)
                return try {
                    val source=String(plain,Charsets.UTF_8)
                    val result=fromPreferences(if(source.isBlank())JSONObject()else JSONObject(source))
                    // storageLock is process-local. A restore/AtomicFile writer
                    // elsewhere must not publish a stale generation as current.
                    stable(files);stable(directory);stable(preferences);stable(pointer)
                    require(originalIdentity==identity()) {"Настройки изменились во время чтения. Повторите позже."}
                    val currentPointer=if(pointer.exists())bytes(pointer,128)else null
                    require(if(pointerBytes==null)currentPointer==null else currentPointer!=null&&pointerBytes.contentEquals(currentPointer)) {
                        "Хранилище изменилось во время чтения. Повторите позже."
                    }
                    result
                }finally {plain.fill(0)}
            }finally {encrypted.fill(0)}
        }
        fun save(context:Context,value:BsScreenOffSettings)=synchronized(ProfileStore.storageLock) {
            fromJson(value.json())
            val store=ProfileStore(context,"preferences.enc");val source=store.read()
            val root=if(source.isBlank())JSONObject()else JSONObject(source)
            root.put(KEY,value.json());store.write(root.toString())
            // Service caches leases by mode only. Refresh an already-active
            // AUTO lease when just the threshold/charger choice changes.
            runCatching {context.sendBroadcast(Intent(CHANGED).setPackage(context.packageName))}
            Unit
        }
    }
}

/** One bounded CPU lease for an owned connected BS session; existing Wi-Fi locks are untouched. */
internal class BsScreenOffLease(private val context:Context,private val settings:BsScreenOffSettings,private val alive:()->Boolean):AutoCloseable {
    private val handler=Handler(Looper.getMainLooper())
    private val power=context.getSystemService(PowerManager::class.java)
    private var lock:PowerManager.WakeLock?=null
    private var closed=false
    private var registered=false
    private var batteryRegistered=false
    private var currentSettings=settings
    private var battery=BsScreenOffBattery()
    private var policyAvailable=true
    private var reported=false
    private var reloadWorker:java.util.concurrent.ExecutorService?=null
    private var reloadRevision=0L
    private var reloadBusy=false
    private var reloadDelivery:Runnable?=null
    private val refresh:Runnable=object:Runnable {override fun run()=synchronized(this@BsScreenOffLease) {
        if(closed)return@synchronized
        handler.removeCallbacks(this)
        try {
            val sessionAlive=alive()
            if(!sessionAlive){close();return@synchronized}
            if(policyAvailable&&currentSettings.holdCpu(power.isInteractive,sessionAlive,power.isPowerSaveMode,battery)) {
                val cpu=lock?:power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"MCRF:BS-screen-off").apply {
                    setReferenceCounted(false)
                }.also {lock=it}
                cpu.acquire(10*60_000L)
                handler.postDelayed(this,5*60_000L)
            }else releaseCpu()
        }catch(_:Exception){
            runCatching {releaseCpu()}
            if(!reported){reported=true;ClientEvents.record(context,"БС","Android не предоставил удержание CPU. Используется обычная фоновая работа.")}
        }
    }}
    private val receiver:BroadcastReceiver=object:BroadcastReceiver(){override fun onReceive(context:Context,intent:Intent)=synchronized(this@BsScreenOffLease) {
        if(!closed) {
            // Only enqueue here: encrypted reads/Keystore must never run on
            // the main receiver thread or while holding the lease monitor.
            reloadRevision++
            policyAvailable=false;runCatching {releaseCpu()}
            if(!reloadBusy)reloadPolicy()
            handler.removeCallbacks(refresh);handler.post(refresh)
        }
        Unit
    }}
    /** Called under the lease monitor. Busy includes the pending main delivery,
     * so bursts conflate to one revision rather than an unbounded task queue. */
    private fun reloadPolicy() {
        if(closed)return
        val revision=reloadRevision
        reloadBusy=true
        val worker=reloadWorker?:java.util.concurrent.Executors.newSingleThreadExecutor {task->
            Thread(task,"bs-screen-off-policy").apply {isDaemon=true}
        }.also {reloadWorker=it}
        try {worker.execute {
            val loaded=try {BsScreenOffSettings.load(context)}catch(_:Exception){null}
            synchronized(this@BsScreenOffLease) {
                if(!closed) {
                    val delivery=Runnable {synchronized(this@BsScreenOffLease) applyPolicy@{
                        reloadDelivery=null
                        if(closed)return@applyPolicy
                        if(revision!=reloadRevision) {
                            reloadBusy=false;reloadPolicy()
                        }else {
                            reloadBusy=false
                            try {
                                if(!alive()){close();return@applyPolicy}
                                if(loaded!=null) {
                                    currentSettings=loaded;updateBatteryObserver();policyAvailable=true
                                }else policyAvailable=false
                            }catch(_:Exception){policyAvailable=false}
                            handler.removeCallbacks(refresh);handler.post(refresh)
                        }
                    }}
                    reloadDelivery=delivery;handler.post(delivery)
                }
            }
        }}catch(_:java.util.concurrent.RejectedExecutionException){reloadBusy=false;policyAvailable=false}
    }
    private val batteryReceiver:BroadcastReceiver=object:BroadcastReceiver(){override fun onReceive(context:Context,intent:Intent)=synchronized(this@BsScreenOffLease) {
        if(!closed&&currentSettings.watchesBattery) {
            battery=BsScreenOffBattery.fromIntent(intent)
            handler.removeCallbacks(refresh);handler.post(refresh)
        }
        Unit
    }}
    private fun register(receiver:BroadcastReceiver,filter:IntentFilter):Intent? =
        if(Build.VERSION.SDK_INT>=33)context.registerReceiver(receiver,filter,Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") context.registerReceiver(receiver,filter)
    private fun updateBatteryObserver() {
        if(currentSettings.watchesBattery&&!batteryRegistered) {
            // Register (and seed from sticky state) only after explicit opt-in.
            val initial=register(batteryReceiver,IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            batteryRegistered=true;battery=BsScreenOffBattery.fromIntent(initial)
        }else if(!currentSettings.watchesBattery&&batteryRegistered) {
            context.unregisterReceiver(batteryReceiver);batteryRegistered=false;battery=BsScreenOffBattery()
        }
    }
    fun start()=synchronized(this) {
        check(!closed&&!registered)
        val filter=IntentFilter(Intent.ACTION_SCREEN_ON).apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(BsScreenOffSettings.CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
        register(receiver,filter)
        registered=true;updateBatteryObserver();handler.post(refresh)
    }
    private fun releaseCpu(){lock?.let {if(it.isHeld)it.release()}}
    override fun close():Unit=synchronized(this) {
        if(closed)return@synchronized
        closed=true;reloadRevision++;handler.removeCallbacks(refresh)
        reloadDelivery?.let(handler::removeCallbacks);reloadDelivery=null
        reloadWorker?.shutdownNow();reloadWorker=null;reloadBusy=false
        if(batteryRegistered){runCatching {context.unregisterReceiver(batteryReceiver)};batteryRegistered=false}
        battery=BsScreenOffBattery()
        if(registered){runCatching {context.unregisterReceiver(receiver)};registered=false}
        runCatching {releaseCpu()}
        Unit
    }
}
