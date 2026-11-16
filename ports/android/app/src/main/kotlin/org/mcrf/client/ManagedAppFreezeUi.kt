package org.mcrf.client

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.widget.LinearLayout
import org.json.JSONArray
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.util.function.Consumer
import java.util.concurrent.Executors

internal object ManagedAppFreezeUi {
    private const val SHIZUKU="moe.shizuku.privileged.api"
    private const val JOURNAL="app-freeze-journal"
    private val executor=Executors.newSingleThreadExecutor {task->Thread(task,"mcrf-app-freeze").apply {isDaemon=true}}
    private const val MANUAL="states"
    private const val AUTOMATIC="automatic-states"
    private const val WATCHED="watched"
    private val handler=Handler(Looper.getMainLooper())
    private var busy=false
    private val loggedFailures=mutableSetOf<String>()
    private var bindFailureLogged=false
    private var lastAvailability:String?=null
    private var owner:Context?=null
    private var listening=false
    private val stateListeners=linkedSetOf<()->Unit>()
    fun addStateListener(listener:()->Unit){stateListeners.add(listener)}
    fun removeStateListener(listener:()->Unit){stateListeners.remove(listener)}
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
            val rules=prefs.contains(WATCHED)||AppLaunchGroupsUi.vpnOnly(context).isNotEmpty()||
                AppLaunchGroupsUi.withoutVpnNotifications(context).isNotEmpty()
            if(rules||auto.isNotEmpty()||manual.isNotEmpty()){
                val availability=when {
                    available->"Shizuku подключён. Управление приложениями доступно."
                    Shizuku.pingBinder()->"У Shizuku нет разрешения на управление приложениями."
                    else->"Shizuku недоступен. Заморозка приостановлена."
                }
                if(lastAvailability!=availability){
                    lastAvailability=availability
                    ClientEvents.record(context,"Shizuku",availability)
                }
            }else lastAvailability=null
            val watched=if(rules||auto.isNotEmpty())desired(context)else emptySet()
            if(!busy&&available){
                val restore=auto-watched
                val freeze=watched.filterTo(mutableSetOf()){id->
                    id !in manual&&(id !in auto||!isFrozen(context,id))&&canManage(context,id)
                }
                if(restore.isNotEmpty())change(context,restore,false,AUTOMATIC){}
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
    internal fun blockReason(context:Context,id:String)=runCatching {ManagedAppFreezePolicy.blockReason(context,id)}
        .getOrElse {"не удалось проверить разрешение Android"}

    internal fun canManage(context:Context,id:String)=blockReason(context,id)==null

    internal fun authorized()=Shizuku.pingBinder()&&
        runCatching {Shizuku.checkSelfPermission()==PackageManager.PERMISSION_GRANTED}.getOrDefault(false)
    private fun desired(context:Context):Set<String>{
        if(ExperienceModeStore(context).load()!=ExperienceMode.EXPERT)return emptySet()
        val only=AppLaunchGroupsUi.vpnOnly(context)
        val ownsTunnel=McrfVpnService.ownsSystemTunnel()
        val without=if(ownsTunnel||externalVpnVisible(context))watched(context)+AppLaunchGroupsUi.withoutVpnNotifications(context)
            else emptySet()
        return if(ownsTunnel)without-only else without+only
    }
    internal fun selectedForFreeze(context:Context,id:String)=id in watched(context)&&id !in AppLaunchGroupsUi.vpnOnly(context)
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
            fun update(){owner?.let(::observe)}
            Shizuku.addBinderReceivedListener {handler.post {update()}}
            Shizuku.addBinderDeadListener {handler.post {update()}}
            Shizuku.addRequestPermissionResultListener {code,result->
                if(code==PERMISSION)handler.post {
                    val activity=permissionOwner?.get()
                    permissionOwner=null
                    update()
                    if(activity!=null&&!activity.isFinishing&&!activity.isDestroyed&&activity.canEditInterface()){
                        if(result==PackageManager.PERMISSION_GRANTED)show(activity)
                        else activity.toast("Разрешение Shizuku не выдано.")
                    }
                }
            }
        }
        handler.removeCallbacks(poll)
        handler.post(poll)
    }

    fun show(activity:MainActivity)=with(activity){
        if(!canEditInterface())return
        val content=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(12),dp(16),dp(8))}
        val installed=runCatching {packageManager.getPackageInfo(SHIZUKU,0)}.isSuccess
        val available=Shizuku.pingBinder()
        val granted=authorized()
        val manual=ids(this,MANUAL)
        val automatic=ids(this,AUTOMATIC)
        val tracked=manual+automatic
        val count=tracked.count {isFrozen(this,it)}
        content.addView(text(when {
            !installed->"Shizuku не установлен."
            !available->if(count>0)"Shizuku недоступен. Заморожено приложений: $count."else"Запустите Shizuku."
            !granted->"Разрешите MCRF управление приложениями в Shizuku."
            else->"Заморожено через MCRF: $count. Замороженные приложения не работают и не получают уведомления."
        },13,Loft.MUTED),space(0,10))
        fun row(label:String,action:()->Unit){
            content.addView(button(label,false).apply {
                if(label=="Запросить разрешение")tag="shizuku-request-permission"
                setOnClickListener {action()}
            },space(0,8))
        }
        lateinit var dialog:android.app.AlertDialog
        when {
            !installed->row("Установить Shizuku"){
                dialog.dismiss()
                ShizukuInstall.start(this@with)
            }
            !available->row("Открыть Shizuku"){
                packageManager.getLaunchIntentForPackage(SHIZUKU)?.let {startActivity(it)}
            }
            !granted->row("Запросить разрешение"){
                permissionOwner=java.lang.ref.WeakReference(this@with)
                dialog.dismiss()
                runCatching {Shizuku.requestPermission(PERMISSION)}.onFailure {
                    permissionOwner=null
                    toast("Не удалось запросить разрешение Shizuku")
                }
            }
            else->{
                fun pick(title:String,selected:Set<String>,onSelection:(Set<String>)->Unit){
                    dialog.dismiss()
                    readPresentation(MainActivity.Operation {
                        val apps=AppPicker.load(this@with,showSystem=true,selected=selected).filter {
                            canManage(this@with,it.id)&&
                                (it.id in selected||packageManager.getLaunchIntentForPackage(it.id)!=null)
                        }
                        runOnUiThread {
                            if(!isFinishing&&!isDestroyed)AppPicker.show(this@with,title,apps,selected,
                                Consumer {ids->onSelection(ids)})
                        }
                        ""
                    })
                }
                row("Заморозить вручную"){
                    pick("Заморозить приложения",emptySet()) {ids->
                        if(ids.isNotEmpty())manualChange(this@with,ids,true)
                    }
                }
                row("Замораживать при подключении VPN"){
                    pick("Заморозка при подключении MCRF",watched(this@with)) {ids->
                        configureAutomatic(this@with,ids)
                        toast(if(ids.isEmpty())"Автоматическая заморозка выключена"else"Список автоматической заморозки сохранён")
                    }
                }
                for(id in tracked.sortedBy {label(this,it).lowercase()}){
                    val name=label(this,id)
                    val state=if(!isFrozen(this,id))"Ожидает проверки"else if(id in manual)"Заморожено вручную"else"Заморожено по VPN"
                    content.addView(LinearLayout(this).apply {
                        gravity=android.view.Gravity.CENTER_VERTICAL
                        addView(text("$name\n$state",13,Loft.INK),LinearLayout.LayoutParams(0,-2,1f))
                        addView(button("Восстановить",false).apply {
                            tag="restore-frozen-app"
                            setOnClickListener {
                                if(restoreOne(this@with,id))dialog.dismiss()
                            }
                        },LinearLayout.LayoutParams(-2,-2))
                    },space(0,6))
                }
                if(tracked.isNotEmpty())row("Восстановить все приложения"){
                    dialog.dismiss()
                    AppLaunchGroupsUi.releaseVpnOnly(this@with,AppLaunchGroupsUi.vpnOnly(this@with))
                    AppLaunchGroupsUi.releaseWithoutVpnNotifications(this@with,AppLaunchGroupsUi.withoutVpnNotifications(this@with))
                    configureAutomatic(this@with,emptySet())
                    if(manual.isNotEmpty())manualChange(this@with,manual,false)
                    else toast("Восстанавливаем приложения")
                }
            }
        }
        dialog=ThemedDialogBuilder(this).setTitle("Заморозка приложений").setView(CompactUi.scroll(this,content)).setNegativeButton("Закрыть",null).create()
        dialog.show()
    }

    internal fun freezeOne(activity:MainActivity,id:String)=manualChange(activity,setOf(id),true)

    internal fun restoreOne(activity:MainActivity,id:String):Boolean {
        if(busy||!authorized()){
            activity.toast("Дождитесь окончания операции или проверьте Shizuku.")
            return false
        }
        val key=when {
            id in ids(activity,MANUAL)->MANUAL
            id in ids(activity,AUTOMATIC)->AUTOMATIC
            else->return false
        }
        AppLaunchGroupsUi.releaseVpnOnly(activity,setOf(id))
        AppLaunchGroupsUi.releaseWithoutVpnNotifications(activity,setOf(id))
        if(id in watched(activity))configureAutomatic(activity,watched(activity)-id)
        change(activity,setOf(id),false,key,reason="вручную") {message->
            if(!activity.isFinishing&&!activity.isDestroyed){activity.render();activity.alert("Заморозка приложений",message)}
        }
        return true
    }

    private fun manualChange(activity:MainActivity,ids:Set<String>,freeze:Boolean){
        if(busy){activity.toast("Операция уже выполняется. Повторите после её завершения.");return}
        if(!authorized()){activity.alert("Shizuku","Запустите Shizuku и разрешите управление.");return}
        change(activity,ids,freeze,MANUAL) {message->
            if(!activity.isDestroyed&&!activity.isFinishing)activity.alert("Заморозка приложений",message)
        }
    }

    private fun change(context:Context,ids:Set<String>,freeze:Boolean,key:String,
                       reason:String=if(key==AUTOMATIC)"правило VPN"else"вручную",complete:(String)->Unit){
        busy=true
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
            stateListeners.toList().forEach {it()}
            if(reconcile)observe(context)
        }
        connection=object:ServiceConnection {
            override fun onServiceConnected(name:ComponentName,service:IBinder){
                if(completed||delivered)return
                delivered=true
                executor.execute {
                    val prefs=journal(context)
                    val states=states(context,key)
                    var done=0
                    val operation=if(freeze)"Заморозка"else"Восстановление"
                    for(id in ids){
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
                                require(before==PackageManager.COMPONENT_ENABLED_STATE_DEFAULT||before==PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
                                if(!states.has(id)){
                                    states.put(id,before)
                                    check(prefs.edit().putString(key,states.toString()).commit())
                                }
                                PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                            }else states.getInt(id)
                            val data=Parcel.obtain();val reply=Parcel.obtain()
                            try{
                                data.writeInterfaceToken(ManagedAppFreezeWorker.DESCRIPTOR)
                                data.writeString(id);data.writeInt(state)
                                check(service.transact(ManagedAppFreezeWorker.CHANGE,data,reply,0))
                                reply.readException()
                            }finally {data.recycle();reply.recycle()}
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
                    handler.post {finish("Обработано: $done из ${ids.size}.",reconcile=done==ids.size)}
                }
            }
            override fun onServiceDisconnected(name:ComponentName){
                if(!delivered)finish("Связь с Shizuku потеряна.",error=true)
            }
        }
        handler.postDelayed({if(!delivered)finish("Shizuku не ответил. Проверьте, запущен ли он.",error=true)},8000)
        try{Shizuku.bindUserService(args,connection)}
        catch(_:Exception){finish("Не удалось подключиться к Shizuku. Проверьте разрешение MCRF.",error=true)}
    }
}
