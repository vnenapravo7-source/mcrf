package org.mcrf.client

import android.app.AppOpsManager
import android.content.*
import android.location.LocationManager
import android.net.*
import android.os.*
import org.json.JSONObject
import java.util.concurrent.Executors

/** Main-process lifetime observation; it never starts an idle VPN or changes its consent. */
internal object NetworkProfilesRuntime {
    private val worker=Executors.newSingleThreadExecutor()
    private val main=Handler(Looper.getMainLooper())
    @Volatile private var monitor:Monitor?=null
    @Volatile var revision=0L;private set
    @Volatile var failure:String?=null;private set
    fun start(context:Context) {
        if(Looper.myLooper()!=Looper.getMainLooper()){main.post {start(context.applicationContext)};return}
        BehaviorScheduleRuntime.start(context)
        if(monitor==null)runCatching {
            val candidate=Monitor(context.applicationContext)
            try {candidate.start();monitor=candidate} catch(error:Exception){candidate.stop();throw error}
        }.onFailure {failure="Наблюдение за сетью недоступно. Настройки сохранены."}
    }
    fun refresh(context:Context) {start(context);main.post {runCatching {monitor?.refresh()}
        .onFailure {failure="Наблюдение за сетью недоступно. Настройки сохранены."}}}
    fun environment(context:Context)=monitor?.environment()?:ConditionProfilesEnvironment.read(context)
    fun current(context:Context):NetworkProfileConfiguration {
        return synchronized(ProfileStore.storageLock) {
            ProfilePolicyRuntime.active(context)?.configuration?.let {return@synchronized it}
            // This store is local to the capture, so no shared RoutingStore lock is inverted.
            val routing=RoutingStore(context).apply {load()}.snapshot().toString()
            val values=ProfileStore.historySnapshot(context)
            NetworkProfileConfiguration.capture(routing,values.getValue("connections.enc"),JSONObject(values.getValue("preferences.enc")))
        }
    }
    /** Capture the current state explicitly for one profile; the fallback is retained independently. */
    fun capture(context:Context,id:String) {
        synchronized(ProfileStore.storageLock) {
            val current=current(context)
            val store=ProfileStore(context,"preferences.enc");val root=JSONObject(store.read().ifBlank {"{}"})
            val section=root.optJSONObject(ConditionProfiles.KEY)?:ConditionProfiles.json(emptyList())
            val profiles=ConditionProfiles.fromJson(section)
            require(id==NetworkProfileSwitch.DEFAULT||profiles.any {it.id==id})
            val next=ConditionProfiles.json(profiles.map {if(it.id==id)it.copy(configuration=current)else it})
                .put("fallback",if(id==NetworkProfileSwitch.DEFAULT)current.json()else section.optJSONObject("fallback")?:current.json())
            if(section.has("activeId"))next.put("activeId",section.getString("activeId"))
            root.put(ConditionProfiles.KEY,next);store.write(root.toString())
            BehaviorScheduleRecovery.recover(context)
        }
        refresh(context)
    }
    /** Shared arbitration owns policy checks, stale-safe commit and configured VPN reconfiguration. */
    private fun apply(context:Context,network:ConditionEnvironment,currentEvidence:()->Boolean) {
        BehaviorScheduleRuntime.evaluateNow(context,network,currentEvidence)
    }
    internal fun configurationApplied()=synchronized(ProfileStore.storageLock) {ProfilePolicyRuntime.invalidate();revision++;Unit}
    fun template(context:Context):JSONObject {
        val prefs=ProfilePolicyRuntime.preferences(context)
        val connections=Connections(context).apply {load()}
        val entries=connections.entries()
        val chosen=entries.firstOrNull {it.id==prefs.getString("selected","")}
            ?:if(prefs.getString("selected","").isNullOrEmpty()&&entries.isEmpty())return JSONObject(ProfileStore(context).read())
            else error("Выбранный сервер сетевого профиля недоступен. Выберите сервер вручную.")
        val bs=chosen.whitelists
        val automatic=prefs.getBoolean("automatic",false)&&BsAutoSelectionPolicy.selected(prefs)!=BsAutoSelectionPolicy.OFF
        val pool=if(automatic)ConnectionSections.entries(entries,bs).filter {it.id in connections.autoIds()}else listOf(chosen)
        require(pool.isNotEmpty()) {"Пул сетевого профиля пуст. Подключение приостановлено."}
        val source=if(!automatic&&chosen.configuration!=null)JSONObject(chosen.configuration!!.toString())
            else if(automatic&&bs)BsPlan.build(pool,prefs.getInt("interval",60),AutoSelectionDeviation.millis(prefs),"Cloudflare")
            else ServerImport.build(pool,chosen.id,automatic,prefs.getInt("interval",60),AutoSelectionDeviation.millis(prefs),"Cloudflare")
        val routing=ProfilePolicyRuntime.routing(context){RoutingStore(context).apply {load()}.snapshot()}
        if(chosen.configuration==null||automatic)DnsConfig.applyGenerated(source,routing.getJSONObject("globalDns"))
        return source
    }
    private class Monitor(private val context:Context) {
        private val manager=context.getSystemService(ConnectivityManager::class.java)
        private val caps=linkedMapOf<Network,NetworkCapabilities>()
        private var callback:ConnectivityManager.NetworkCallback?=null
        private var receiverRegistered=false
        private var stopped=false
        @Volatile private var evidenceGeneration=0L
        fun environment()=ConditionProfilesEnvironment.read(context,synchronized(caps){caps.toMap()})
        private val tick=Runnable {
            val stamp=evidenceGeneration
            val environment=environment()
            worker.execute {
                try {
                    apply(context,environment){stamp==evidenceGeneration}
                    failure=null
                }catch(_:Exception){failure="Не удалось применить сетевой профиль. Проверьте сохранённые маршруты и серверы."}
            }
        }
        private fun schedule(){evidenceGeneration++;main.removeCallbacks(tick);main.postDelayed(tick,500)}
        private fun refreshSafely(){runCatching {refresh()}.onFailure {failure="Наблюдение за сетью недоступно. Настройки сохранены."}}
        private val receiver=object:BroadcastReceiver(){override fun onReceive(context:Context,intent:Intent){refreshSafely()}}
        private val permissions=AppOpsManager.OnOpChangedListener {_,packageName->if(packageName==null||packageName==context.packageName)main.post {refreshSafely()}}
        fun start() {
            val filter=IntentFilter(LocationManager.MODE_CHANGED_ACTION)
            if(Build.VERSION.SDK_INT>=33)context.registerReceiver(receiver,filter,Context.RECEIVER_NOT_EXPORTED)
            else @Suppress("DEPRECATION") context.registerReceiver(receiver,filter)
            receiverRegistered=true
            val ops=context.getSystemService(AppOpsManager::class.java)
            for(op in listOf(AppOpsManager.OPSTR_FINE_LOCATION,AppOpsManager.OPSTR_COARSE_LOCATION))
                ops.startWatchingMode(op,context.packageName,AppOpsManager.WATCH_FOREGROUND_CHANGES,permissions)
            refresh()
        }
        fun stop() {
            stopped=true
            evidenceGeneration++;main.removeCallbacks(tick)
            callback?.let {runCatching {manager.unregisterNetworkCallback(it)}};callback=null
            synchronized(caps){caps.clear()}
            if(receiverRegistered)runCatching {context.unregisterReceiver(receiver)}
            receiverRegistered=false
            runCatching {context.getSystemService(AppOpsManager::class.java).stopWatchingMode(permissions)}
        }
        fun refresh() {
            check(!stopped)
            evidenceGeneration++
            main.removeCallbacks(tick)
            callback?.let {runCatching {manager.unregisterNetworkCallback(it)}};callback=null;synchronized(caps){caps.clear()}
            fun update(owner:ConnectivityManager.NetworkCallback,network:Network,value:NetworkCapabilities?) {
                if(callback!==owner)return // Queued callbacks from an unregistered observer are stale.
                synchronized(caps){if(value==null)caps.remove(network)else caps[network]=value}
                schedule()
            }
            val next=if(Build.VERSION.SDK_INT>=31)object:ConnectivityManager.NetworkCallback(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) {
                override fun onCapabilitiesChanged(network:Network,value:NetworkCapabilities){update(this,network,value)}
                override fun onLost(network:Network){update(this,network,null)}
            }else object:ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network:Network,value:NetworkCapabilities){update(this,network,value)}
                override fun onLost(network:Network){update(this,network,null)}
            }
            callback=next
            manager.registerNetworkCallback(NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(),next,main)
            schedule()
        }
    }
}
