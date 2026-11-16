package org.mcrf.client

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** One arbiter for network callbacks, foreground timers and durable recovery jobs. Never opens UI. */
internal object BehaviorScheduleRuntime {
    private val started=AtomicBoolean()
    private val executionLock=Any()
    private val main=Handler(Looper.getMainLooper())
    private val worker=Executors.newSingleThreadExecutor()
    private var monitor:Monitor?=null
    @Volatile var nextRun:Instant?=null;private set
    @Volatile var status="Выключено";private set
    fun start(context:Context) {
        if(started.compareAndSet(false,true))main.post {
            runCatching {Monitor(context.applicationContext).also {monitor=it;it.start()}}
                .onFailure {
                    status="Наблюдение недоступно; сохранённые задания восстановления остаются."
                    worker.execute {runCatching {BehaviorScheduleRecovery.schedule(context,nextRun)}}
                }
        }
    }
    fun refresh(context:Context) {start(context);main.post {monitor?.schedule()}}
    fun rearmRecovery(context:Context) {worker.execute {runCatching {BehaviorScheduleRecovery.schedule(context,nextRun)}}}
    internal fun environment(context:Context,network:ConditionEnvironment?=null):BehaviorSchedule.Environment {
        val battery=context.registerReceiver(null,IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level=battery?.getIntExtra(BatteryManager.EXTRA_LEVEL,-1)?:-1
        val scale=battery?.getIntExtra(BatteryManager.EXTRA_SCALE,-1)?:-1
        val state=battery?.getIntExtra(BatteryManager.EXTRA_STATUS,-1)
        return BehaviorSchedule.Environment(network?:ConditionProfilesEnvironment.read(context),
            when(state){BatteryManager.BATTERY_STATUS_CHARGING,BatteryManager.BATTERY_STATUS_FULL->true
                BatteryManager.BATTERY_STATUS_DISCHARGING,BatteryManager.BATTERY_STATUS_NOT_CHARGING->false;else->null},
            if(level>=0&&scale>0&&level<=scale)(level.toLong()*100/scale).toInt()else null,
            ConditionProfilesApply.vpnActive())
    }
    private fun decision(values:Map<String,String>,env:BehaviorSchedule.Environment):BehaviorSchedule.Decision {
        val root=JSONObject(values.getValue("preferences.enc").ifBlank {"{}"})
        val profiles=root.optJSONObject(ConditionProfiles.KEY)?.let(ConditionProfiles::fromJson).orEmpty()
        val settings=root.optJSONObject(BehaviorSchedule.KEY)?.let(BehaviorSchedule::fromJson)?:BehaviorSchedule.Settings()
        val now=Instant.now();val zone=ZoneId.systemDefault()
        val decision=BehaviorSchedule.evaluate(settings,now,zone,env,profiles.filter {it.enabled&&it.configuration!=null}.map {it.id}.toSet())
        val boundary=BehaviorSchedule.nextProfileBoundary(profiles,now,zone,env)
        return decision.copy(nextRun=listOfNotNull(decision.nextRun,boundary).minOrNull())
    }
    /** The winner reserves precedence even while awaiting approval or deferred for the active VPN. */
    fun evaluateNow(context:Context,network:ConditionEnvironment?=null,evidenceCurrent:()->Boolean={true}) {
        synchronized(executionLock) {
            try {
                if(!evidenceCurrent())return
                nextRun=null
                if(SafeModeStore(context).enabled()){nextRun=null;status="Приостановлено: безопасный режим";return}
                val env=environment(context,network)
                ProfilePolicyRuntime.invalidate()
                synchronized(ProfileStore.storageLock) {
                    val before=ProfileStore.historySnapshot(context)
                    val selected=decision(before,env);nextRun=selected.nextRun
                    val root=JSONObject(before.getValue("preferences.enc").ifBlank {"{}"})
                    val section=root.optJSONObject(ConditionProfiles.KEY)
                    val lease=ChangePreviewPolicy.lease(context)
                    val rule=selected.rule
                    fun current(expectedRule:String?):Boolean {
                        if(!evidenceCurrent()||!lease.current(context)||SafeModeStore(context).enabled())return false
                        val fresh=environment(context)
                        return fresh==env&&decision(ProfileStore.historySnapshot(context),fresh).rule?.id==expectedRule
                    }
                    if(rule!=null) {
                        if(!lease.permitsRule(rule,section)){status="Правило ожидает подтверждения автополитики";return}
                        val allowActive=lease.allowActiveNetwork||rule.action.allowActiveVpnReconfiguration
                        if(env.vpnActive&&!allowActive){status="Отложено до отключения VPN · включите автоприменение повторно";return}
                        if(rule.action.profileId!=null) {
                            if(section?.optString("activeId")==rule.action.profileId){status="Профиль управляет подключением";return}
                            val preview=ConditionProfilesApply.prepareSelection(before,rule.action.profileId,preserveApproved=true)
                            if(preview.hasChanges)ConditionProfilesApply.apply(context,preview,allowActive) {
                                current(rule.id)&&lease.permitsRule(rule,section)
                            }
                            status="Снимок сетевого профиля правила применён"
                        }else {
                            applyToggles(context,before,rule) {
                                current(rule.id)&&lease.permitsRule(rule,section)&&
                                    (!ConditionProfilesApply.vpnActive()||allowActive)
                            }
                            status="Безопасные переключатели правила применены"
                        }
                        return
                    }
                    if(section==null){status="Нет подходящих правил";return}
                    // Arbitration and nextRun are already resolved, including an unapproved
                    // higher-priority rule. No receipt can authorize a network transition:
                    // do not construct an expensive, unusable preview while holding storageLock.
                    if(lease.tokens.isEmpty()){status="Автоприменение выключено";return}
                    val plan=ConditionProfilesApply.networkPlan(before,env.network,authoritative=true)?:run {status="Сетевой профиль уже выбран";return}
                    if(!lease.permitsProfile(section,plan.targetId)||!lease.permitsProfile(plan.section,plan.targetId)) {
                        status="Сетевой снимок ожидает подтверждения автополитики";return
                    }
                    if(env.vpnActive&&!lease.allowActiveNetwork){status="Автопрофиль отложен до отключения VPN";return}
                    ConditionProfilesApply.apply(context,plan.preview,lease.allowActiveNetwork) {
                        current(null)&&lease.permitsProfile(section,plan.targetId)&&lease.permitsProfile(plan.section,plan.targetId)&&
                            (ConditionProfiles.resolve(ConditionProfiles.fromJson(section),env.network,false)?.id?:NetworkProfileSwitch.DEFAULT)==plan.targetId
                    }
                    status="Подтверждённый сетевой снимок применён"
                }
            }finally {
                if(evidenceCurrent())runCatching {BehaviorScheduleRecovery.schedule(context,nextRun)}
                    .onFailure {status="Не удалось сохранить фоновое задание. Откройте расписание повторно."}
            }
        }
    }
    private fun applyToggles(context:Context,before:Map<String,String>,rule:BehaviorSchedule.Rule,current:()->Boolean) {
        val preferences=JSONObject(before.getValue("preferences.enc").ifBlank {"{}"})
        val plain=context.getSharedPreferences("MainActivity",Context.MODE_PRIVATE)
        if(rule.action.toggles.all {(key,value)->preferences.optBoolean(key.key,false)==value&&plain.getBoolean(key.key,false)==value})return
        rule.action.toggles.forEach {(key,value)->preferences.put(key.key,value)}
        val preview=ChangePreview.prepare(before,before+("preferences.enc" to preferences.toString()))
        preview.apply({ProfileStore.historySnapshot(context)},{AndroidBackup.validatePreferences(JSONObject(it.getValue("preferences.enc")));require(current()) {"Условия или политика изменились."}}) {values->
            require(current()) {"Условия или политика изменились."}
            require(ChangeHistoryStore.local(context).checkpoint(before)) {"Не удалось сохранить точку восстановления."}
            ProfileStore.withoutHistory {ProfileStore(context,"preferences.enc").write(values.getValue("preferences.enc"))}
            val editor=plain.edit();rule.action.toggles.forEach {(key,value)->editor.putBoolean(key.key,value)}
            check(editor.commit())
            NetworkProfilesRuntime.configurationApplied()
        }
    }
    fun runScheduled(context:Context,cancelled:()->Boolean,done:(Boolean)->Unit) {
        worker.execute {
            var retry=false
            try {if(!cancelled())evaluateNow(context,evidenceCurrent={!cancelled()})}
            catch(_:Exception){retry=true;status="Фоновая проверка отложена. Настройки и подтверждения проверятся повторно."}
            finally {done(retry)}
        }
    }
    private class Monitor(private val context:Context) {
        private val wake=Runnable {schedule()}
        private val tick=Runnable {
            worker.execute {
                try {evaluateNow(context)}catch(_:Exception){status="Не удалось применить правило. Проверьте расписание и профили."}
                finally {main.post {
                    val delay=nextRun?.let {java.time.Duration.between(Instant.now(),it).toMillis().coerceIn(1000,60_000)}?:60_000L
                    main.removeCallbacks(wake);main.postDelayed(wake,delay)
                }}
            }
        }
        private val receiver=object:BroadcastReceiver(){override fun onReceive(context:Context,intent:Intent){schedule()}}
        private val networks=object:ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network:Network){schedule()}
            override fun onLost(network:Network){schedule()}
            override fun onCapabilitiesChanged(network:Network,capabilities:NetworkCapabilities){schedule()}
        }
        fun start() {
            val filter=IntentFilter().apply {
                addAction(Intent.ACTION_BATTERY_CHANGED);addAction(Intent.ACTION_POWER_CONNECTED);addAction(Intent.ACTION_POWER_DISCONNECTED)
                addAction(Intent.ACTION_TIME_CHANGED);addAction(Intent.ACTION_TIMEZONE_CHANGED);addAction(Intent.ACTION_DATE_CHANGED);addAction(Intent.ACTION_SCREEN_ON)
            }
            if(Build.VERSION.SDK_INT>=33)context.registerReceiver(receiver,filter,Context.RECEIVER_NOT_EXPORTED)
            else @Suppress("DEPRECATION") context.registerReceiver(receiver,filter)
            context.getSystemService(ConnectivityManager::class.java).registerNetworkCallback(NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),networks,main)
            schedule()
        }
        fun schedule(){main.removeCallbacks(tick);main.postDelayed(tick,250)}
    }
}
