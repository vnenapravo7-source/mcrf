package org.mcrf.client

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** One-shot tickets protect commands that need a foreground Activity. */
internal object QuickTileCommand {
    const val EXTRA="mcrf.quickTileTicket"
    private const val STORE="QuickTileCommands"
    private const val PREFIX="ticket."
    private val allowed=setOf("vpn","bs","rules","auto-bs","anubis")
    private fun allowed(command:String?)=command in allowed
    internal fun valid(ticket:String?,expected:String?,command:String?,age:Long)=
        !ticket.isNullOrEmpty()&&ticket==expected&&allowed(command)&&age in 0..60_000
    internal fun decode(ticket:String,record:String?,now:Long):String?=runCatching {
        if(!ticket.matches(Regex("[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}"))||record==null)return@runCatching null
        val value=org.json.JSONObject(record)
        if(value.keys().asSequence().toSet()!=setOf("command","at")||value.get("command") !is String)return@runCatching null
        val at=value.get("at")
        if((at !is Long&&at !is Int)||value.getLong("at")<0)return@runCatching null
        val command=value.optString("command")
        command.takeIf {valid(ticket,ticket,it,now-value.optLong("at",-1))}
    }.getOrNull()
    @Synchronized fun issue(context:Context,command:String):String {
        require(allowed(command))
        val ticket=java.util.UUID.randomUUID().toString()
        val now=android.os.SystemClock.elapsedRealtime()
        val prefs=context.getSharedPreferences(STORE,Context.MODE_PRIVATE)
        val edit=prefs.edit().remove("ticket").remove("command").remove("at")
        var pending=0
        prefs.all.forEach {(key,value)->if(key.startsWith(PREFIX)) {
            if(decode(key.removePrefix(PREFIX),value as? String,now)==null)edit.remove(key)else pending++
        }}
        check(pending<16) {"Откройте MCRF и дождитесь обработки предыдущих нажатий."}
        val record=org.json.JSONObject().put("command",command).put("at",now).toString()
        check(edit.putString(PREFIX+ticket,record).commit())
        return ticket
    }
    @Synchronized fun consume(context:Context,intent:Intent):String? {
        val ticket=intent.getStringExtra(EXTRA)?:return null
        intent.removeExtra(EXTRA)
        val prefs=context.getSharedPreferences(STORE,Context.MODE_PRIVATE)
        val command=decode(ticket,prefs.getString(PREFIX+ticket,null),android.os.SystemClock.elapsedRealtime())
        if(!prefs.edit().remove(PREFIX+ticket).commit())return null
        return command
    }
}

abstract class McrfQuickTile:TileService() {
    protected abstract val command:String
    protected abstract val title:String
    protected abstract val iconId:Int
    private val handler=Handler(Looper.getMainLooper())
    private val worker=java.util.concurrent.Executors.newSingleThreadExecutor()
    @Volatile private var listening=false
    @Volatile private var refreshing=false
    private var listeningEpoch=0L
    private val tileIcon by lazy { QuickTileAvailability.previewIcon(this,iconId) }
    private fun notifyUser(message:String) = feedbackHandler.post {
        if((command=="vpn"||command=="bs")&&message.startsWith("VK Flood"))
            LastConnectionFailure.remember(message)
        feedback[command]=message
        qsTile?.apply { subtitle=message;updateTile() }
        toast?.cancel()
        toast=android.widget.Toast.makeText(applicationContext,message,android.widget.Toast.LENGTH_SHORT).also {it.show()}
        if(command=="rules"||command=="auto-bs") {
            val manager=getSystemService(android.app.NotificationManager::class.java)
            if(manager.areNotificationsEnabled()) {
                val channel="quick-tile-feedback"
                manager.createNotificationChannel(android.app.NotificationChannel(channel,"Действия в шторке",android.app.NotificationManager.IMPORTANCE_DEFAULT))
                val id=if(command=="rules")4201 else 4202
                manager.cancel(id)
                manager.notify(id,android.app.Notification.Builder(applicationContext,channel)
                    .setSmallIcon(iconId).setContentTitle(title).setContentText(message)
                    .setTimeoutAfter(4500).build())
            }
        }
    }
    private val refresh=object:Runnable {override fun run(){
        if(!listening)return
        if(refreshing){handler.postDelayed(this,1500);return}
        refreshing=true
        val captured=listeningEpoch
        worker.execute {
            val state=runCatching {
                val prefs=getSharedPreferences("MainActivity",MODE_PRIVATE)
                when(command) {
                    "anubis"->(false to title).takeIf {AnubisRouteAccess.allowed(this@McrfQuickTile)}?:error("Anubis is unavailable")
                    "auto-bs"->prefs.getBoolean(BsDetectionMonitor.KEY,false) to title
                    "rules"->{val routes=RoutingStore(this@McrfQuickTile).also {it.load()}.snapshot()
                        false to groupLabel(routes)}
                    else->error("Unknown tile")
                }
            }
            handler.post {
                if(captured!=listeningEpoch)return@post
                refreshing=false
                if(listening)qsTile?.apply {
                    label=if(command=="rules")state.getOrNull()?.second?:title else title
                    icon=tileIcon
                    this.state=state.fold({if(it.first)Tile.STATE_ACTIVE else Tile.STATE_INACTIVE},{Tile.STATE_UNAVAILABLE})
                    subtitle=feedback[command]?:if(command=="rules")title else null
                    updateTile()
                }
            }
        }
    }}
    private fun refreshNow(){
        if(!listening)return
        if(command=="vpn"||command=="bs") {
            val status=McrfVpnService.status
            val bs=McrfVpnService.pendingWhitelists==true
            val active=QuickTilePresentation.active(command,status,bs)
            qsTile?.apply {
                label=title
                icon=tileIcon
                state=if(active)Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                subtitle=if(ConnectionPresentation.pending(status)&&bs==(command=="bs"))"Подключение…"else null
                updateTile()
            }
        }else{handler.removeCallbacks(refresh);handler.post(refresh)}
    }
    override fun onTileAdded(){super.onTileAdded();requestListeningState(this,ComponentName(this,javaClass))}
    override fun onStartListening(){super.onStartListening();listeningEpoch++;refreshing=false;listening=true;listeningTiles.add(this);refreshNow()}
    override fun onStopListening(){listeningTiles.remove(this);listeningEpoch++;refreshing=false;listening=false;handler.removeCallbacksAndMessages(null);super.onStopListening()}
    override fun onDestroy(){listeningTiles.remove(this);listening=false;handler.removeCallbacksAndMessages(null);worker.shutdownNow();super.onDestroy()}
    // Intent overload is required on API29-33; PendingIntent exists from34.
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick(){super.onClick();if(isLocked)unlockAndRun(::runTileClick) else runTileClick()}
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    private fun runTileClick() {
        if(command!="anubis"&&(command !in setOf("vpn","bs")||android.net.VpnService.prepare(this)==null)) {
            val context=applicationContext
            commands.execute {
                runCatching { executeCommand(context,command) }.onSuccess {message->if(message!=null)notifyUser(message)}
                    .onFailure {notifyUser(it.message?:"Действие не выполнено")}
                handler.post {refreshNow();refresh(context)}
            }
            return
        }
        val ticket=runCatching {QuickTileCommand.issue(this,command)}.getOrNull()?:run {
            android.widget.Toast.makeText(this,"Откройте MCRF и повторите нажатие после завершения текущего действия.",android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val intent=Intent(this,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .setAction("org.mcrf.client.QUICK_TILE.$ticket")
            .putExtra(QuickTileCommand.EXTRA,ticket)
        if(Build.VERSION.SDK_INT>=34)startActivityAndCollapse(PendingIntent.getActivity(this,0,intent,PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE))
        else @Suppress("DEPRECATION") startActivityAndCollapse(intent)
    }
    companion object {
        private val feedbackHandler=Handler(Looper.getMainLooper())
        private val feedback=mutableMapOf<String,String>()
        private var toast:android.widget.Toast?=null
        private var lastConnectionState:Pair<String,Boolean?>?=null
        private val listeningTiles=mutableSetOf<McrfQuickTile>()
        internal fun connectionChanged(context:Context) {
            feedbackHandler.post {
                McrfHomeWidget.refresh(context)
                val state=McrfVpnService.status to McrfVpnService.pendingWhitelists
                if(state==lastConnectionState)return@post
                lastConnectionState=state
                listeningTiles.filter {it.command=="vpn"||it.command=="bs"}.forEach {it.refreshNow()}
                for(service in listOf(VpnQuickTile::class.java,BsQuickTile::class.java))
                    requestListeningState(context,ComponentName(context,service))
            }
        }
        private val commands=java.util.concurrent.Executors.newSingleThreadExecutor()
        internal fun executeInOrder(task: Runnable) = commands.execute(task)
        private fun groupLabel(state:org.json.JSONObject)=when(RoutingGroups.active(state)) {
            "vpn"->"VPN"
            "bs"->"БС"
            "auto"->"Авто"
            else->RoutingGroups.groups(state).firstOrNull {it.first==RoutingGroups.active(state)}?.second?:QuickTileAvailability.ROUTE_TITLE
        }
        private fun executeCommand(context:Context,command:String):String? {
            val prefs=context.getSharedPreferences("MainActivity",Context.MODE_PRIVATE)
            val catalogue=Connections(context).apply {load()}
            when(command) {
                "auto-bs"->{
                    val enabled=!prefs.getBoolean(BsDetectionMonitor.KEY,false)
                    setBsDetection(context, enabled, catalogue)
                    return if(enabled)"Авто-БС включен" else "Авто-БС выключен"
                }
                "rules"->{
                    check(!McrfVpnService.cleanupPending()&&!ConnectionPresentation.pending(McrfVpnService.status)){"Дождитесь завершения подключения"}
                    val store=RoutingStore(context).apply {load()}
                    val (snapshot,generation)=store.snapshotWithGeneration()
                    val next=RoutingGroups.next(snapshot)
                    val result=store.editInGeneration(generation){ state->
                        RoutingGroups.select(state,next)
                        if(McrfVpnService.hasActiveSession()) {
                            val plan=RouteServerPlan(state,catalogue.entries())
                            RouteServerSession.check(context,plan,java.util.function.BooleanSupplier {false})
                            require(plan.entries.none {it.carrier?.optBoolean("rt_masque")==true}||
                                WdttMasqueStore.accepted(context)&&WdttMasqueStore.load(context)!=null){"Подготовьте MASQUE в MCRF"}
                        }
                    }
                    McrfVpnService.reloadActiveRoutes(context)
                    return "Группа маршрутов: ${groupLabel(result)}"
                }
            }
            val bs=command=="bs"
            check(!EngineUpdates.installationPending(context)&&!McrfVpnService.cleanupPending()&&
                !ConnectionPresentation.pending(McrfVpnService.status)){"Дождитесь завершения текущего действия"}
            check(android.net.VpnService.prepare(context)==null){"Разрешите VPN в MCRF"}
            if(QuickTilePresentation.active(command,McrfVpnService.status,McrfVpnService.pendingWhitelists==true)&&
                McrfVpnService.ownsSystemTunnel()) {
                context.startService(Intent(context,McrfVpnService::class.java).setAction(McrfVpnService.STOP))
                return null
            }
            val remembered=BsAutoSelectionPolicy.last(prefs,bs)
            val entry=SectionConnectSelection.choose(catalogue.entries(),remembered,bs,catalogue.probes())
                ?:error(if(bs)"В разделе БС пока нет серверов" else "В разделе VPN пока нет серверов")
            startServer(context, entry, true)
            return null
        }

        internal fun setBsDetection(context: Context, enabled: Boolean, catalogue: Connections) {
            if (enabled) {
                require(catalogue.entries().any { AutoSelection.eligible(it, AutoSelection.BS_ENGINES) }) {
                    "Сначала добавьте поддерживаемый сервер БС"
                }
                require(ClientPrimarySelection.selected(context) != "steer") { "Для Авто-БС выберите sing-box" }
            }
            val prefs = context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE)
            check(prefs.edit().putBoolean(BsDetectionMonitor.KEY, enabled).commit())
            McrfVpnService.refreshBsEnvironment(context)
        }

        internal fun startServer(context: Context, entry: ServerImport.Server, vpn: Boolean) {
            val prefs = context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE)
            check(!TrafficCaptureService.blocksServerStart()) { "Сначала остановите режим Только запись" }
            check(!EngineUpdates.installationPending(context) && !EngineUpdateScripts.mutationPending()) {
                "Завершите обновление движка перед подключением"
            }
            check(!vpn || android.net.VpnService.prepare(context) == null) { "Разрешите VPN для подключения" }
            entry.carrier?.let {
                NativeCarrier.executable(context,EngineModules.carrierKind(it))
                NativeCarrier.requireOptions(context,it)
                if(it.optString("type") in SwgProfile.TYPES)
                    NativeCarrier.confirmConnectionPing(context,it,java.util.function.BooleanSupplier {
                        Thread.currentThread().isInterrupted
                    })
            }
            val routes=RoutingStore(context).apply {load()}.snapshot()
            val source=ServerImport.build(listOf(entry),entry.id,false,60,0,"Cloudflare")
            if(entry.configuration==null)DnsConfig.applyGenerated(source,routes.getJSONObject("globalDns"))
            val standalone=StandaloneRouterSelection.hint(context,entry,vpn)&&
                StandaloneRouterSelection.preflight(context,entry,source,RoutingConnectionBinding.forManualVpn(routes),
                    java.util.function.BooleanSupplier {Thread.currentThread().isInterrupted})!=null
            if(!standalone&&entry.carrier?.let {BsNativeContract.connectionAvailable(context,it,vpn)}!=true&&
                !WgAddonPolicy.standaloneProfile(entry,vpn,false)) {
                val primary=ClientPrimarySelection.requireSelected(context)
                EngineUpdatePrimary.installation(context,EngineUpdatePrimary.kindForChoice(primary))
            }
            check(!McrfVpnService.cleanupPending() && !ConnectionPresentation.pending(McrfVpnService.status)) {
                "Дождитесь завершения текущего действия"
            }
            ProfileStore(context).write(source.toString())
            check(prefs.edit().remove("warpSelectionJournal").putString("selected",entry.id).putBoolean("automatic",false).putBoolean("tun",vpn)
                .putBoolean("catalogueProfile",true).commit())
            BsAutoSelectionPolicy.remember(prefs,entry)
            val start=Intent(context,McrfVpnService::class.java).setAction(McrfVpnService.START)
                .putExtra("tun",vpn).putExtra("port",prefs.getInt("port",10808))
                .putExtra("applyRoutes",standalone||prefs.getBoolean("applyRoutes",true))
                .putExtra(McrfVpnService.MANUAL_VPN,true).putExtra(McrfVpnService.EXPLICIT_TILE_START,true).putExtra("pendingWhitelists",entry.whitelists)
            if(McrfVpnService.hasActiveSession())context.startService(start) else context.startForegroundService(start)
        }
        fun refresh(context:Context) {
            listOf(VpnQuickTile::class.java,BsQuickTile::class.java,RulesQuickTile::class.java,AutoBsQuickTile::class.java,AnubisQuickTile::class.java).forEach {
                requestListeningState(context,ComponentName(context,it))
            }
        }
    }
}
class VpnQuickTile:McrfQuickTile(){override val command="vpn";override val title="VPN";override val iconId=R.drawable.qs_rkn_diamond}
class BsQuickTile:McrfQuickTile(){override val command="bs";override val title="БС";override val iconId=R.drawable.qs_bs}
class RulesQuickTile:McrfQuickTile(){override val command="rules";override val title=QuickTileAvailability.ROUTE_TITLE;override val iconId=R.drawable.qs_rules}
class AutoBsQuickTile:McrfQuickTile(){override val command="auto-bs";override val title="Авто-БС";override val iconId=R.drawable.qs_auto_bs}

class AnubisQuickTile:McrfQuickTile(){override val command="anubis";override val title="Anubis";override val iconId=R.drawable.qs_anubis}
