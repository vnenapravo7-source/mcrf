package org.mcrf.client

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Tiles enter the same selection/STOP/consent path as the app sidebar. A
 * one-shot private ticket prevents exported Activity intents forging commands. */
internal object QuickTileCommand {
    const val EXTRA="mcrf.quickTileTicket"
    private const val STORE="QuickTileCommands"
    private const val PREFIX="ticket."
    private val allowed=setOf("vpn","bs","rules","auto-bs")
    internal fun valid(ticket:String?,expected:String?,command:String?,age:Long)=
        !ticket.isNullOrEmpty()&&ticket==expected&&command in allowed&&age in 0..60_000
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
        require(command in allowed)
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
    private var lastSelected:String?=null
    private var selectedIsBs=false
    private val refresh=object:Runnable {override fun run(){
        if(!listening)return
        if(refreshing){handler.postDelayed(this,1500);return}
        refreshing=true
        val captured=listeningEpoch
        worker.execute {
            val state=runCatching {
                val prefs=getSharedPreferences("MainActivity",MODE_PRIVATE)
                when(command) {
                    "auto-bs"->prefs.getBoolean(BsDetectionMonitor.KEY,false) to title
                    "rules"->{val routes=RoutingStore(this@McrfQuickTile).also {it.load()}.snapshot()
                        false to RoutingGroups.groups(routes).single {it.first==RoutingGroups.active(routes)}.second}
                    else->{val selected=McrfVpnService.selectedId
                        if(lastSelected!=selected){
                            val catalogue=Connections(this@McrfQuickTile).also {it.load()}
                            selectedIsBs=catalogue.entries().firstOrNull {it.id==selected}?.carrier!=null
                            lastSelected=selected
                        }
                        QuickTilePresentation.active(command,McrfVpnService.status,selectedIsBs) to title}
                }
            }
            handler.post {
                if(captured!=listeningEpoch)return@post
                refreshing=false
                if(listening)qsTile?.apply {
                    label=title;icon=Icon.createWithResource(this@McrfQuickTile,iconId)
                    this.state=state.fold({if(it.first)Tile.STATE_ACTIVE else Tile.STATE_INACTIVE},{Tile.STATE_UNAVAILABLE})
                    if(Build.VERSION.SDK_INT>=29)subtitle=state.getOrNull()?.second
                    updateTile()
                }
            }
        }
        if(command in setOf("vpn","bs"))handler.postDelayed(this,1500)
    }}
    override fun onStartListening(){super.onStartListening();listeningEpoch++;refreshing=false;listening=true;handler.removeCallbacks(refresh);handler.post(refresh)}
    override fun onStopListening(){listeningEpoch++;refreshing=false;listening=false;handler.removeCallbacksAndMessages(null);super.onStopListening()}
    override fun onDestroy(){listening=false;handler.removeCallbacksAndMessages(null);worker.shutdownNow();super.onDestroy()}
    // Intent overload is required on API29-33; PendingIntent exists from34.
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick(){super.onClick();unlockAndRun {
        val ticket=runCatching {QuickTileCommand.issue(this,command)}.getOrNull()?:run {
            android.widget.Toast.makeText(this,"Откройте MCRF и повторите нажатие после завершения текущего действия.",android.widget.Toast.LENGTH_SHORT).show()
            return@unlockAndRun
        }
        val intent=Intent(this,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .setAction("org.mcrf.client.QUICK_TILE.$ticket")
            .putExtra(QuickTileCommand.EXTRA,ticket)
        if(Build.VERSION.SDK_INT>=34)startActivityAndCollapse(PendingIntent.getActivity(this,0,intent,PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE))
        else @Suppress("DEPRECATION") startActivityAndCollapse(intent)
    }}
    companion object {
        fun refresh(context:Context) {
            listOf(VpnQuickTile::class.java,BsQuickTile::class.java,RulesQuickTile::class.java,AutoBsQuickTile::class.java).forEach {
                requestListeningState(context,ComponentName(context,it))
            }
        }
    }
}
class VpnQuickTile:McrfQuickTile(){override val command="vpn";override val title="VPN";override val iconId=R.drawable.qs_vpn}
class BsQuickTile:McrfQuickTile(){override val command="bs";override val title="БС";override val iconId=R.drawable.qs_bs}
class RulesQuickTile:McrfQuickTile(){override val command="rules";override val title="Rules";override val iconId=R.drawable.qs_rules}
class AutoBsQuickTile:McrfQuickTile(){override val command="auto-bs";override val title="Авто-БС";override val iconId=R.drawable.qs_auto_bs}
