package org.mcrf.client

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import org.json.JSONArray
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.io.Closeable
import java.util.concurrent.Executors

internal object ManagedAppFreezeUi {
    private const val SHIZUKU="moe.shizuku.privileged.api"
    private const val JOURNAL="app-freeze-journal"
    private val executor=Executors.newSingleThreadExecutor {task->Thread(task,"mcrf-app-freeze").apply {isDaemon=true}}
    private val pairingExecutor=Executors.newSingleThreadExecutor {task->Thread(task,"mcrf-anubis-pairing").apply {isDaemon=true}}
    private var wirelessSession:Closeable?=null
    private var wirelessDiscovery:Closeable?=null
    private var wirelessRecovery:AnubisPairingNative.Attempt?=null
    private var nextWirelessRecoveryAt=0L
    private const val MANUAL="states"
    private const val AUTOMATIC="automatic-states"
    private const val WATCHED="watched"
    private val handler=Handler(Looper.getMainLooper())
    private var busy=false
    private enum class Provider { SHIZUKU, ADB }
    private const val PROVIDER="provider"
    @Volatile private var provider=Provider.SHIZUKU
    private val loggedFailures=mutableSetOf<String>()
    private var bindFailureLogged=false
    private var lastAvailability:String?=null
    private var owner:Context?=null
    private var listening=false
    private var permissionOwner:java.lang.ref.WeakReference<MainActivity>?=null
    private const val PERMISSION=8512
    private val poll=object:Runnable {
        override fun run(){
            val context=owner?:return
            val prefs=journal(context)
            val auto=ids(context,AUTOMATIC)
            val manual=ids(context,MANUAL)
            val available=authorized()
            AppFreezeRecoveryNotice.update(context,if(available)0 else (manual+auto).count {isFrozen(context,it)})
            val rules=prefs.contains(WATCHED)||AppLaunchGroupsUi.vpnOnly(context).isNotEmpty()
            if(rules||auto.isNotEmpty()||manual.isNotEmpty()){
                val availability=when {
                    provider==Provider.ADB&&available->"Встроенный ADB подключён."
                    provider==Provider.ADB->"Встроенный ADB недоступен. Заморозка приостановлена."
                    available->"Shizuku подключён. Управление приложениями доступно."
                    Shizuku.pingBinder()->"У Shizuku нет разрешения на управление приложениями."
                    else->"Shizuku недоступен. Заморозка приостановлена."
                }
                if(lastAvailability!=availability){
                    lastAvailability=availability
                    ClientEvents.record(context,"Anubis",availability)
                }
            }else lastAvailability=null
            val watched=if(rules||auto.isNotEmpty())desired(context)else emptySet()
            if(!available&&(rules||auto.isNotEmpty()||manual.isNotEmpty()))recoverWirelessAccess(context)
            if(!busy&&available){
                val restore=auto-watched+auto.filter {id->
                    runCatching {context.packageManager.getApplicationEnabledSetting(id)==
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED}.getOrDefault(false)
                }
                val freeze=watched.filterTo(mutableSetOf()){id->
                    id !in manual&&(id !in auto||!isFrozen(context,id))&&canManage(context,id)
                }
                if(restore.isNotEmpty())change(context,restore,false,AUTOMATIC){}
                else if(manual.isNotEmpty())change(context,manual,false,MANUAL,reason="восстановление после обновления Anubis"){}
                else if(freeze.isNotEmpty())change(context,freeze,true,AUTOMATIC){}
            }
            if(auto.isNotEmpty()||manual.isNotEmpty()||rules)
                handler.postDelayed(this,if(available&&(auto.isNotEmpty()||rules))3000 else 15000)
        }
    }

    private fun journal(context:Context)=context.getSharedPreferences(JOURNAL,0)
    private fun states(context:Context,key:String)=JSONObject(journal(context).getString(key,"{}").orEmpty())
    private fun ids(context:Context,key:String)=states(context,key).keys().asSequence().toSet()
    internal fun isFrozen(context:Context,id:String)=runCatching {
        context.packageManager.getApplicationEnabledSetting(id)==PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
    }.getOrDefault(false)
    private fun label(context:Context,id:String)=runCatching {
        context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(id,0)).toString()
    }.getOrDefault(id)
    internal fun blockReason(context:Context,id:String)=
        if(context.packageName==ManagedAppFreezeAdb.UI_CLONE_PACKAGE&&id==ManagedAppFreezeAdb.PACKAGE)
            "основное приложение MCRF"
        else runCatching {
            ManagedAppFreezePolicy.blockReason(context,id) ?: when(context.packageManager.getApplicationEnabledSetting(id)){
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED->"Сначала откройте приложение или включите его в настройках Android."
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED->"Приложение отключено в настройках Android."
                else->null
            }
        }
            .getOrElse {"не удалось проверить разрешение Android"}

    internal fun canManage(context:Context,id:String)=blockReason(context,id)==null

    internal fun authorized()=when(provider) {
        Provider.ADB->ManagedAppFreezeAdb.enabled
        Provider.SHIZUKU->Shizuku.pingBinder()&&
            runCatching {Shizuku.checkSelfPermission()==PackageManager.PERMISSION_GRANTED}.getOrDefault(false)
    }

    fun adbAvailabilityChanged(context:Context) {
        handler.post {
            if(!ManagedAppFreezeAdb.enabled)releaseWirelessSession()
            if(provider==Provider.ADB)observe(context)
        }
    }

    private fun recoverWirelessAccess(context:Context){
        val now=android.os.SystemClock.elapsedRealtime()
        if(provider!=Provider.ADB||wirelessDiscovery!=null||wirelessRecovery!=null||now<nextWirelessRecoveryAt||
            !AnubisRouteAccess.allowed(context)||!AnubisPairingNative.available(context)||
            !wirelessDebuggingEnabled(context)||!AnubisPairingNative.hasSavedKey())return
        nextWirelessRecoveryAt=now+30_000
        fun clearDiscovery(){
            wirelessDiscovery?.close()
            wirelessDiscovery=null
        }
        var discovery:Closeable?=null
        val expire=Runnable {if(wirelessDiscovery===discovery)clearDiscovery()}
        wirelessDiscovery=AnubisAdbDiscovery.start(context) {ports->
            val port=ports.connect?:return@start
            handler.removeCallbacks(expire)
            clearDiscovery()
            if(provider!=Provider.ADB||authorized())return@start
            val attempt=AnubisPairingNative.Attempt()
            wirelessRecovery=attempt
            pairingExecutor.execute {
                val session=runCatching {AnubisPairingNative.reconnect(context,port,attempt)}.getOrNull()
                handler.post {
                    if(wirelessRecovery!==attempt||provider!=Provider.ADB||!AnubisRouteAccess.allowed(context)){
                        if(session!=null)pairingExecutor.execute {session.close()}
                        return@post
                    }
                    wirelessRecovery=null
                    if(session!=null){
                        releaseWirelessSession()
                        wirelessSession=session
                        observe(context)
                    }
                }
            }
        }
        discovery=wirelessDiscovery
        handler.postDelayed(expire,12_000)
    }

    private fun cancelWirelessRecovery(){
        wirelessDiscovery?.close()
        wirelessDiscovery=null
        wirelessRecovery?.cancel()
        wirelessRecovery=null
    }

    private fun releaseWirelessSession() {
        val session=wirelessSession?:return
        wirelessSession=null
        pairingExecutor.execute {runCatching {session.close()}}
    }

    private fun selectProvider(context:Context,next:Provider) {
        journal(context).edit().putString(PROVIDER,next.name).apply()
        if(provider==next)return
        if(provider==Provider.ADB){
            cancelWirelessRecovery()
            releaseWirelessSession()
            ManagedAppFreezeAdb.detach()?.let {binder->
                executor.execute {runCatching {ManagedAppFreezeAdb.stop(binder)}}
            }
        }
        provider=next
    }
    private fun desired(context:Context):Set<String>{
        if(!AnubisRouteAccess.allowed(context))return emptySet()
        val ownsTunnel=McrfVpnService.ownsSystemTunnel()
        return ManagedAppFreezePolicy.automaticTargets(
            watched(context),AppLaunchGroupsUi.vpnOnly(context),
            ownsTunnel,
            externalVpn=!ownsTunnel&&externalVpnVisible(context)
        )
    }
    internal fun selectedForFreeze(context:Context,id:String)=
        id in watched(context)&&id !in AppLaunchGroupsUi.vpnOnly(context)
    private fun externalVpnVisible(context:Context)=runCatching {
        val connectivity=context.getSystemService(android.net.ConnectivityManager::class.java)
        connectivity.allNetworks.any {network->
            connectivity.getNetworkCapabilities(network)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)==true
        }
    }.getOrDefault(false)

    internal fun waitingForAutomaticRestore(context:Context,id:String)=states(context,AUTOMATIC).has(id)
    internal fun watched(context:Context):Set<String>{
        val array=JSONArray(journal(context).getString(WATCHED,"[]").orEmpty())
        return (0 until array.length()).map(array::getString).toSet()
    }
    internal fun configureAutomatic(context:Context,apps:Set<String>){
        val edit=journal(context).edit()
        if(apps.isEmpty())edit.remove(WATCHED) else edit.putString(WATCHED,JSONArray(apps.toList()).toString())
        check(edit.commit())
        observe(context)
    }
    private var lastTunnelState=false
    fun onTunnelChanged(context:Context){
        handler.post {
            val connected=McrfVpnService.ownsSystemTunnel()
            if(connected!=lastTunnelState){
                lastTunnelState=connected
                observe(context)
            }
        }
    }

    fun observe(context:Context){
        owner=context.applicationContext
        if(!listening){
            listening=true
            provider=Provider.entries.firstOrNull {it.name==journal(context).getString(PROVIDER,null)}?:Provider.SHIZUKU
            fun update(){owner?.let(::observe)}
            fun retryWirelessOnNetworkChange(){
                handler.post {
                    if(provider==Provider.ADB&&!authorized()){
                        nextWirelessRecoveryAt=0L
                        update()
                    }
                }
            }
            runCatching {
                val manager=context.getSystemService(android.net.ConnectivityManager::class.java)
                manager.registerNetworkCallback(android.net.NetworkRequest.Builder()
                    .addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI).build(),
                    object:android.net.ConnectivityManager.NetworkCallback(){
                        override fun onAvailable(network:android.net.Network)=retryWirelessOnNetworkChange()
                    })
            }
            context.contentResolver.registerContentObserver(Settings.Global.getUriFor("adb_wifi_enabled"),false,
                object:android.database.ContentObserver(handler){
                    override fun onChange(selfChange:Boolean)=retryWirelessOnNetworkChange()
                })
            Shizuku.addBinderReceivedListener {handler.post {update()}}
            Shizuku.addBinderDeadListener {handler.post {update()}}
            Shizuku.addRequestPermissionResultListener {code,result->
                if(code==PERMISSION)handler.post {
                    val activity=permissionOwner?.get()
                    permissionOwner=null
                    update()
                    if(activity!=null&&!activity.isFinishing&&!activity.isDestroyed&&AnubisRouteAccess.allowed(activity)){
                        if(result==PackageManager.PERMISSION_GRANTED)activity.render()
                        else activity.toast("Разрешение Shizuku не выдано.")
                    }
                }
            }
        }
        handler.removeCallbacks(poll)
        handler.post(poll)
    }

    fun providerChosen(context:Context)=journal(context).contains(PROVIDER)

    fun providerSummary(context:Context):String=when(journal(context).getString(PROVIDER,null)){
        Provider.SHIZUKU.name->if(authorized())"Shizuku · подключён" else "Shizuku · требуется настройка"
        Provider.ADB.name->when {
            authorized()->"Встроенный MCRF · подключён"
            AnubisPairingNative.available(context)&&!wirelessDebuggingEnabled(context)->
                "Встроенный MCRF · включите беспроводную отладку"
            else->"Встроенный MCRF · требуется настройка ADB"
        }
        else->"Выберите Shizuku или встроенный MCRF"
    }

    fun show(activity:MainActivity):Unit=with(activity){
        if(!AnubisRouteAccess.allowed(activity)||!AnubisLaunchObserver.enabled(activity))return
        if(busy){toast("Дождитесь окончания операции.");return}
        val wireless=AnubisPairingNative.available(this)
        val description=if(wireless)
            "Подключите встроенный MCRF через беспроводную отладку на этом телефоне. Shizuku использует отдельное приложение."
        else wirelessUnavailable(this)
        val body=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(16),dp(4),dp(16),dp(12))
            addView(text(description,13,Loft.MUTED),space(0,12))
        }
        val actions=LinearLayout(this).apply {orientation=LinearLayout.HORIZONTAL}
        body.addView(actions)
        val dialog=ThemedDialogBuilder(this).setTitle("Управление приложениями").setView(body).create()
        fun action(label:String,accent:Boolean,weight:Float,onClick:()->Unit) {
            val child=button(label,accent).apply {
                setOnClickListener {
                    if(busy){toast("Дождитесь окончания операции.");return@setOnClickListener}
                    dialog.dismiss()
                    onClick()
                }
            }
            actions.addView(child,LinearLayout.LayoutParams(0,-2,weight).apply {
                if(actions.childCount!=0)marginStart=dp(8)
            })
        }
        action("Shizuku",false,1f) {
            selectProvider(this,Provider.SHIZUKU)
            observe(this)
            render()
            showShizukuSetup(this)
        }
        if(wireless)action("Встроенный MCRF",true,1.7f) {
            selectProvider(this,Provider.ADB)
            observe(this)
            render()
            showAdbSetup(this)
        }
        dialog.show()
    }

    private fun showShizukuSetup(activity:MainActivity):Unit=with(activity){
        val installed=runCatching {packageManager.getPackageInfo(SHIZUKU,0)}.isSuccess
        val available=Shizuku.pingBinder()
        val dialog=ThemedDialogBuilder(this,compactActions=true).setTitle("Shizuku").setNegativeButton("Закрыть",null)
        when {
            authorized()->dialog.setMessage("Shizuku подключён. Правила Anubis могут управлять приложениями.")
            !installed&&!available->dialog.setMessage("Установите Shizuku и запустите его по инструкции в приложении.")
                .setPositiveButton("Установить Shizuku"){_,_->ShizukuInstall.start(this)}
            !available->dialog.setMessage("Откройте Shizuku, запустите службу и вернитесь в Anubis.")
                .setPositiveButton("Открыть Shizuku"){_,_->
                    packageManager.getLaunchIntentForPackage(SHIZUKU)?.let {startActivity(it)}
                }
            else->dialog.setMessage("Разрешите MCRF управлять приложениями через Shizuku.")
                .setPositiveButton("Запросить разрешение"){_,_->
                    permissionOwner=java.lang.ref.WeakReference(this)
                    runCatching {Shizuku.requestPermission(PERMISSION)}.onFailure {
                        permissionOwner=null
                        toast("Не удалось запросить разрешение Shizuku")
                    }
                }
        }
        dialog.show().getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.tag="shizuku-request-permission"
    }

    private fun wirelessUnavailable(context:Context)=when {
        android.os.Build.VERSION.SDK_INT<android.os.Build.VERSION_CODES.R ->
            "Для встроенного управления нужна беспроводная отладка Android 11 или новее."
        !ManagedAppFreezeAdb.supports(context) ->
            "Встроенное управление доступно в основном профиле Android."
        else -> "В этой сборке отсутствует компонент сопряжения. Обновите MCRF или выберите Shizuku."
    }

    private fun showAdbSetup(activity:MainActivity):Unit=with(activity){
        if(authorized()){
            alert("Встроенный MCRF","ADB подключён. Правила Anubis могут управлять приложениями.")
            return
        }
        if(!AnubisPairingNative.available(this)){
            alert("Встроенный MCRF",wirelessUnavailable(this))
            return
        }
        if(wirelessDebuggingEnabled(this))showWirelessAdbSetup(this) else showWirelessAdbEnable(this)
    }

    private fun wirelessDebuggingEnabled(activity:Context)=runCatching {
        Settings.Global.getInt(activity.contentResolver,"adb_wifi_enabled",0)!=0
    }.getOrDefault(false)

    private fun developerOptionsEnabled(activity:MainActivity)=runCatching {
        Settings.Global.getInt(activity.contentResolver,Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,0)!=0
    }.getOrDefault(true)

    private fun openWirelessSettings(activity:MainActivity):Unit=with(activity){
        val direct=runCatching {startActivity(Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS").setPackage("com.android.settings").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}.isSuccess
        if(!direct)runCatching {startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(":settings:fragment_args_key","enable_adb_wireless"))}
            .onFailure {toast("Откройте «Параметры разработчика → Беспроводная отладка».")}
    }

    private fun showWirelessAdbEnable(activity:MainActivity):Unit=with(activity){
        val body=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(16),dp(4),dp(16),dp(12))
            addView(text("В параметрах разработчика включите «Беспроводную отладку».\n\nЕсли параметров разработчика нет, откройте сведения о телефоне и нажмите «Номер сборки» 7 раз. На Samsung этот пункт находится в «Сведения о ПО».\n\nВернитесь в MCRF и нажмите «Далее».",14,Loft.INK),space(0,12))
        }
        val dialog=ThemedDialogBuilder(this).setTitle("1 из 2 · Включите Wi‑Fi-отладку").setView(body).create()
        val actions=LinearLayout(this).apply {orientation=LinearLayout.HORIZONTAL;gravity=android.view.Gravity.CENTER_VERTICAL}
        fun action(label:String,tagName:String,weight:Float,description:String=label,onClick:()->Unit) {
            val control=button(label,false).apply {
                StandardButtons.compact(this@with,this,label)
                setPadding(dp(4),dp(4),dp(4),dp(4))
                tag=tagName
                contentDescription=description
                setOnClickListener {onClick()}
            }
            actions.addView(control,LinearLayout.LayoutParams(0,-2,weight).apply {
                if(actions.childCount>0)marginStart=dp(6)
            })
        }
        action("Настройки","anubis-wireless-settings",1.3f,"Открыть настройки") {
            if(developerOptionsEnabled(this))openWirelessSettings(this)
            else runCatching {startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}
                .onFailure {toast("Откройте сведения о телефоне в настройках Android.")}
        }
        action("Закрыть","anubis-wireless-close",1f) {dialog.dismiss()}
        action("Далее","anubis-wireless-next",1f) {
            if(!wirelessDebuggingEnabled(this))toast("Сначала включите беспроводную отладку в настройках Android.")
            else {
                dialog.dismiss()
                showWirelessAdbSetup(this)
            }
        }
        body.addView(actions)
        dialog.show()
    }

    private fun showWirelessAdbSetup(activity:MainActivity):Unit=with(activity){
        fun numberField(label:String,max:Int,password:Boolean=false)=EditText(this).apply {
            hint=label
            contentDescription=label
            inputType=InputType.TYPE_CLASS_NUMBER or (if(password)InputType.TYPE_NUMBER_VARIATION_PASSWORD else 0)
            filters=arrayOf(InputFilter.LengthFilter(max))
            isSingleLine=true
            minHeight=dp(48)
            setPaddingRelative(dp(12),dp(8),dp(12),dp(8))
            isSaveEnabled=false
            importantForAutofill=android.view.View.IMPORTANT_FOR_AUTOFILL_NO
        }
        val connectPort=numberField("Порт подключения",5)
        val pairPort=numberField("Порт сопряжения",5)
        val codeField=numberField("Шестизначный код",6,true)
        val reconnect=button("Запустить без нового кода",true).takeIf {AnubisPairingNative.hasSavedKey()}
        val discoveryStatus=text("Ищем порты этого телефона…",13,Loft.MUTED)
        var discovery:java.io.Closeable?=null
        var returningFromSettings=false
        var lastConnectionPort:String?=null
        var lastPairingPort:String?=null
        fun findPorts() {
            discovery?.close()
            discoveryStatus.text="Ищем порты этого телефона…"
            discovery=AnubisAdbDiscovery.start(this) { ports ->
                fun fill(field:EditText,previous:String?,port:Int?):String? {
                    val value=port?.toString()?:return previous
                    val current=field.text.toString()
                    if(current.isNotEmpty()&&current!=previous)return previous
                    if(current!=value)field.setText(value)
                    return value
                }
                lastConnectionPort=fill(connectPort,lastConnectionPort,ports.connect)
                lastPairingPort=fill(pairPort,lastPairingPort,ports.pairing)
                discoveryStatus.text=when {
                    ports.connect!=null&&ports.pairing!=null->"Порты найдены. Введите код Android."
                    ports.connect!=null->"Порт подключения найден. Откройте окно с кодом Android."
                    ports.pairing!=null->"Порт сопряжения найден. Укажите порт подключения."
                    else->"Порты можно ввести вручную из настроек Android."
                }
            }
        }
        val instructions=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(16),dp(12),dp(16),dp(16))
            addView(text("Откройте «Подключить устройство с помощью кода».",13,Loft.MUTED),space(0,6))
            addView(text("Введите шестизначный код Android и нажмите «Подключить».",13,Loft.MUTED),space(0,6))
            addView(text("Если окно с кодом закрывается, откройте MCRF и настройки в разделённом экране.",13,Loft.MUTED),space(0,8))
            addView(button("Открыть беспроводную отладку",false).apply {
                setOnClickListener {
                    returningFromSettings=true
                    findPorts()
                    openWirelessSettings(this@with)
                }
            },space(4,12))
            addView(discoveryStatus,space(0,14))
            for((label,field) in listOf("Порт подключения" to connectPort,"Порт сопряжения" to pairPort,"Шестизначный код" to codeField)) {
                addView(text(label,13,Loft.MUTED).apply {setPaddingRelative(dp(12),0,dp(12),0)},space(0,6))
                addView(field,space(0,14))
            }
            addView(button("Найти порты снова",false).apply {setOnClickListener {findPorts()}},space(4,10))
            reconnect?.let {addView(it,space(0,8))}
        }
        var attempt:AnubisPairingNative.Attempt?=null
        val dialog=ThemedDialogBuilder(this,compactActions=true).setTitle("2 из 2 · Данные подключения")
            .setView(CompactUi.scroll(this,instructions))
            .setPositiveButton("Подключить",null)
            .setNegativeButton("Закрыть",null).show()
        dialog.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        val decor=dialog.window?.decorView
        val focusListener=android.view.ViewTreeObserver.OnWindowFocusChangeListener { focused ->
            if(focused&&returningFromSettings&&dialog.isShowing&&attempt==null){
                returningFromSettings=false
                findPorts()
            }
        }
        decor?.viewTreeObserver?.addOnWindowFocusChangeListener(focusListener)
        dialog.setOnDismissListener {
            decor?.viewTreeObserver?.takeIf {it.isAlive}?.removeOnWindowFocusChangeListener(focusListener)
            codeField.text.clear()
            discovery?.close()
            discovery=null
            attempt?.cancel()
            attempt=null
        }
        val submit=dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
        findPorts()
        fun connect(connection:Int,pairing:Int?,code:ByteArray?) {
            cancelWirelessRecovery()
            nextWirelessRecoveryAt=android.os.SystemClock.elapsedRealtime()+30_000
            codeField.text.clear()
            discovery?.close()
            discovery=null
            val current=AnubisPairingNative.Attempt()
            attempt=current
            submit.isEnabled=false
            reconnect?.isEnabled=false
            discoveryStatus.text=if(code==null)"Запускаем Anubis…" else "Проверяем код и подключаем Anubis…"
            val app=applicationContext
            pairingExecutor.execute {
                val result=try {
                    runCatching {
                        if(code!=null)AnubisPairingNative.pairAndLaunch(app,requireNotNull(pairing),code,connection,current)
                        else AnubisPairingNative.reconnect(app,connection,current)
                    }
                } finally {code?.fill(0)}
                handler.post {
                    val session=result.getOrNull()
                    if(attempt!==current||!dialog.isShowing||isFinishing||isDestroyed||provider!=Provider.ADB){
                        if(session!=null)pairingExecutor.execute {runCatching {session.close()}}
                        return@post
                    }
                    attempt=null
                    submit.isEnabled=true
                    reconnect?.isEnabled=true
                    if(session==null){
                        discoveryStatus.text="Подключение не прошло. Откройте новое окно с кодом Android."
                        toast("Не удалось подключиться. Проверьте оба порта и получите новый код Android.")
                        return@post
                    }
                    releaseWirelessSession()
                    wirelessSession=session
                    dialog.dismiss()
                    observe(this)
                    render()
                    toast("ADB подключён.")
                }
            }
        }
        submit.setOnClickListener {
            if(attempt!=null)return@setOnClickListener
            val connection=connectPort.text.toString().toIntOrNull()
            val pairing=pairPort.text.toString().toIntOrNull()
            val entered=codeField.text
            if(connection==null||connection !in 1..65535||pairing==null||pairing !in 1..65535||pairing==connection||
                entered.length!=6||entered.any {it !in '0'..'9'}){
                toast("Укажите два разных порта и шестизначный код Android.")
                return@setOnClickListener
            }
            connect(connection,pairing,ByteArray(6){entered[it].code.toByte()})
        }
        reconnect?.setOnClickListener {
            if(attempt!=null)return@setOnClickListener
            val port=connectPort.text.toString().toIntOrNull()
            if(port==null||port !in 1..65535)toast("Укажите порт подключения из настроек Android.")
            else connect(port,null,null)
        }
    }

    fun resumeSetup(activity:MainActivity){
        if(AnubisRouteAccess.allowed(activity)&&AnubisLaunchObserver.enabled(activity))showShizukuSetup(activity)
    }
    internal fun restoreOne(activity:MainActivity,id:String):Boolean {
        if(busy||!authorized()){
            activity.toast("Дождитесь окончания операции или проверьте доступ к управлению приложениями.")
            return false
        }
        val key=when {
            id in ids(activity,MANUAL)->MANUAL
            id in ids(activity,AUTOMATIC)->AUTOMATIC
            else->return false
        }
        AppLaunchGroupsUi.releaseVpnOnly(activity,setOf(id))
        if(id in watched(activity))configureAutomatic(activity,watched(activity)-id)
        change(activity,setOf(id),false,key,reason="вручную") {message->
            if(!activity.isFinishing&&!activity.isDestroyed){activity.render();activity.alert("Заморозка приложений",message)}
        }
        return true
    }

    private fun change(context:Context,ids:Set<String>,freeze:Boolean,key:String,
                       reason:String=if(key==AUTOMATIC)"правило VPN"else"вручную",complete:(String)->Unit){
        busy=true
        if(provider!=Provider.SHIZUKU) {
            val applyState:(String,Int)->Unit={id,state->ManagedAppFreezeAdb.change(context,id,state)}
            applyChanges(context,ids,freeze,key,reason,applyState) {message,ok->
                busy=false
                complete(message)
                if(ok||!authorized())observe(context)
            }
            return
        }
        val args=Shizuku.UserServiceArgs(ComponentName(context,ManagedAppFreezeWorker::class.java))
            .tag("mcrf-app-freeze").processNameSuffix("app-freeze").daemon(false).version(3)
        var delivered=false
        var completed=false
        lateinit var connection:ServiceConnection
        fun finish(message:String,error:Boolean=false,reconcile:Boolean=false){
            if(completed)return
            completed=true
            busy=false
            runCatching {Shizuku.unbindUserService(args,connection,true)}
            if(error&&!bindFailureLogged){
                bindFailureLogged=true
                ClientEvents.record(context,"Anubis",message)
            }else if(!error)bindFailureLogged=false
            complete(message)
            if(reconcile)observe(context)
        }
        connection=object:ServiceConnection {
            override fun onServiceConnected(name:ComponentName,service:IBinder){
                if(completed||delivered)return
                delivered=true
                applyChanges(context,ids,freeze,key,reason,{id,state->
                    val data=Parcel.obtain();val reply=Parcel.obtain()
                    try{
                        data.writeInterfaceToken(ManagedAppFreezeWorker.DESCRIPTOR)
                        data.writeString(id);data.writeInt(state)
                        check(service.transact(ManagedAppFreezeWorker.CHANGE,data,reply,0))
                        reply.readException()
                    }finally {data.recycle();reply.recycle()}
                }) { message,ok->finish(message,reconcile=ok) }
            }
            override fun onServiceDisconnected(name:ComponentName){
                if(!delivered)finish("Связь с Shizuku потеряна.",error=true)
            }
        }
        handler.postDelayed({if(!delivered)finish("Shizuku не ответил. Проверьте, запущен ли он.",error=true)},8000)
        try{Shizuku.bindUserService(args,connection)}
        catch(_:Exception){finish("Не удалось подключиться к Shizuku. Проверьте разрешение MCRF.",error=true)}
    }
    private fun applyChanges(context:Context,ids:Set<String>,freeze:Boolean,key:String,reason:String,
                             applyState:(String,Int)->Unit,finished:(String,Boolean)->Unit) {
        executor.execute {
            val prefs=journal(context)
            val states=runCatching {states(context,key)}.getOrElse {
                handler.post {finished("Не удалось прочитать журнал восстановления приложений.",false)}
                return@execute
            }
            var done=0
            val operation=if(freeze)"Заморозка"else"Восстановление"
            for(id in ids){
                if(provider!=Provider.SHIZUKU&&!authorized())break
                val failureKey="$key/$freeze/$id"
                runCatching {
                    val pm=context.packageManager
                    if(!freeze&&runCatching {pm.getApplicationInfo(id,0)}.exceptionOrNull() is PackageManager.NameNotFoundException){
                        states.remove(id)
                        check(prefs.edit().putString(key,states.toString()).commit())
                        done++
                        return@runCatching
                    }
                    val state=if(freeze){
                        require(canManage(context,id))
                        if(key==AUTOMATIC&&id !in desired(context))return@runCatching
                        val before=pm.getApplicationEnabledSetting(id)
                        if(states.has(id)&&before==PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER){
                            done++
                            return@runCatching
                        }
                        require(before in setOf(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                            PackageManager.COMPONENT_ENABLED_STATE_ENABLED))
                        if(!states.has(id)){
                            states.put(id,before)
                            check(prefs.edit().putString(key,states.toString()).commit())
                        }
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                    }else states.getInt(id)
                    if(freeze||pm.getApplicationEnabledSetting(id)!=state)applyState(id,state)
                    if(!freeze){
                        states.remove(id)
                        check(prefs.edit().putString(key,states.toString()).commit())
                    }
                    done++
                    loggedFailures.remove(failureKey)
                    ClientEvents.record(context,"Anubis","$operation · ${label(context,id)} · $reason.")
                }.onFailure {
                    if(loggedFailures.add(failureKey))ClientEvents.record(context,"Anubis",
                        "$operation · ошибка · ${label(context,id)} · $reason · ${it.javaClass.simpleName}.")
                }
            }
            handler.post {finished("Обработано: $done из ${ids.size}.",done==ids.size)}
        }
    }
}
