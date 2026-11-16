package org.mcrf.client

import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.app.Activity
import java.util.concurrent.Executors

/** 2.0 presentation only. Profiles stay untouched; Android owns every mutation. */
internal object EngineUpdateMigrationUi {
    private const val DECLINED="bsMigration20Declined"
    private val worker=Executors.newSingleThreadExecutor()
    private var checking=false
    private var lastScan:Long?=null
    private var dialog:AlertDialog?=null
    private var finished=false
    /** Keeps the launch/dismiss gap from reopening the migration prompt before Android owns the uninstall UI. */
    private var launchingRemoval=false
    private var queue=ArrayDeque<String>()
    private var pending:Pair<Int,String>?=null
    fun isShowing()=dialog?.isShowing==true

    fun offer(activity:MainActivity) {
        if(BuildConfig.VERSION_NAME.substringBefore('.').toIntOrNull()?.let {it>=2}!=true)return
        if(finished||checking||launchingRemoval||isShowing()||!activity.canOfferEngineUpdates()||activity.active()||ProbeCoordinator.measurements!=null)return
        val now=SystemClock.elapsedRealtime()
        if(lastScan?.let {now-it<5000}==true)return
        checking=true;lastScan=now
        val app=activity.applicationContext
        worker.execute {
            val result=runCatching {
                val legacy=EngineUpdateScreenPolicy.legacyKinds.filter {kind->
                    try {app.packageManager.getApplicationInfo(EngineModules.packageName(kind),0);true}
                    catch(_:PackageManager.NameNotFoundException){false}
                }
                val replacement=runCatching {BsEnginePackage.installation(app);true}.getOrDefault(false)
                // Never interpret an Activity's not-yet-loaded cache as no servers.
                val kinds=if(legacy.isEmpty())emptyList()else runCatching {
                    Connections(app).apply {load()}.entries().map {it.carrier?.optString("type")?.takeIf(String::isNotEmpty)?:it.protocol}
                }.getOrNull()
                Triple(legacy,replacement,EngineUpdateScreenPolicy.canDecline(kinds))
            }
            activity.runOnUiThread {
                checking=false
                if(!activity.canOfferEngineUpdates()||activity.active()||ProbeCoordinator.measurements!=null||AppUpdates.blocksOtherPrompt()||EngineStartupUpdates.isShowing())return@runOnUiThread
                val (legacy,replacement,canDecline)=result.getOrNull()?:return@runOnUiThread
                val phase=EngineUpdateScreenPolicy.migration(legacy,replacement)
                if(phase==EngineUpdateScreenPolicy.Migration.NONE){finished=true;return@runOnUiThread}
                val fingerprint=legacy.sorted().joinToString(",")
                if(canDecline&&activity.prefs.getString(DECLINED,null)==fingerprint)return@runOnUiThread
                val builder=ThemedDialogBuilder(activity).setTitle("Переход на единый движок БС CSQTT+QWDTT")
                    .setMessage("В MCRF 2.0 старые пакеты CSQTT / QWDTT заменяет единый BS Engine. Сначала установите новый пакет, затем удалите старые через Android. Подключения и настройки сохранятся.\n\n"+
                        if(replacement)"Новый движок проверен. Осталось удалить старых пакетов: ${legacy.size}."else"Старые пакеты останутся на месте до проверки нового движка.")
                    .setPositiveButton(if(replacement)"Удалить старые"else"Установить новый"){_,_->
                        lastScan=null
                        if(replacement){
                            queue=ArrayDeque(legacy);launchingRemoval=true;removeNext(activity)
                        }else activity.installEngine(BsEngineContract.KIND)
                    }
                if(canDecline)builder.setNegativeButton("Оставить старые"){_,_->
                    activity.prefs.edit().putString(DECLINED,fingerprint).apply()
                }
                dialog=builder.create().also {shown->
                    shown.setCancelable(canDecline);shown.setCanceledOnTouchOutside(canDecline)
                    shown.setOnDismissListener {if(dialog===shown)dialog=null}
                    shown.show()
                }
            }
        }
    }

    private fun removeNext(activity:MainActivity) {
        val kind=queue.firstOrNull()?:run {
            launchingRemoval=false;finished=true
            activity.alert("Переход завершён","Старые пакеты CSQTT и QWDTT удалены. Единый движок БС CSQTT+QWDTT готов к работе.")
            return
        }
        // This is invoked from an AlertDialog button. The dialog owns window focus,
        // so canOfferEngineUpdates() (a background-prompt admission check) is false here.
        if(kind !in EngineUpdateScreenPolicy.legacyKinds||activity.isFinishing||activity.isDestroyed||activity.working()||
            activity.active()||ProbeCoordinator.measurements!=null||EngineUpdates.installationPending(activity)){
            launchingRemoval=false;return
        }
        // Logical CSQTT now resolves to BS. Delete only the EXACT old package ID.
        worker.execute {
            val verified=runCatching {BsEnginePackage.installation(activity.applicationContext);true}.getOrDefault(false)
            // installation() briefly owns a read lease. Wait for that exact lease to retire
            // instead of treating the transient collision as a completed button action.
            var lease:EngineUpdateGate.Mutation?=null
            if(verified&&!activity.active()&&ProbeCoordinator.measurements==null) {
                val deadline=SystemClock.elapsedRealtime()+5000
                while(lease==null&&SystemClock.elapsedRealtime()<deadline) {
                    lease=runCatching {EngineUpdateGate.beginMutation(activity,"bs_migration20","remove",kind)}.getOrNull()
                    if(lease==null)Thread.sleep(50)
                }
            }
            activity.runOnUiThread {
                if(!verified||activity.isFinishing||activity.isDestroyed||activity.active()||ProbeCoordinator.measurements!=null){
                    lease?.let {owned->EngineUpdateGate.complete(activity,owned.pending.token){true}}
                    launchingRemoval=false;return@runOnUiThread
                }
                val owned=lease?:run{
                    launchingRemoval=false
                    activity.alert("Миграция движков","Не удалось освободить процессы движков для удаления. Нажмите «Удалить старые» ещё раз.")
                    return@runOnUiThread
                }
                try {
                    @Suppress("DEPRECATION") val intent=Intent(Intent.ACTION_UNINSTALL_PACKAGE,Uri.parse("package:${EngineModules.packageName(kind)}"))
                        .putExtra(Intent.EXTRA_RETURN_RESULT,true)
                    pending=owned.pending.requestCode to kind
                    activity.startActivityForResult(intent,owned.pending.requestCode)
                }catch(_:Exception) {
                    pending=null;launchingRemoval=false;queue.clear()
                    EngineUpdateGate.complete(activity,owned.pending.token){true}
                    activity.alert("Миграция движков","Не удалось открыть удаление Android. Повторите попытку.")
                }
            }
        }
    }

    fun returned(activity:MainActivity,request:Int,result:Int):Boolean {
        val step=pending?.takeIf {it.first==request}?:return false
        pending=null
        worker.execute {
            runCatching {EngineUpdates.applyInstallerResult(activity.applicationContext,request,result)}
            val absent=try {
                activity.packageManager.getApplicationInfo(EngineModules.packageName(step.second),0);false
            }catch(_:PackageManager.NameNotFoundException){true}
            val replacement=runCatching {BsEnginePackage.installation(activity.applicationContext);true}.getOrDefault(false)
            activity.runOnUiThread {
                if(result==Activity.RESULT_OK&&absent&&replacement&&queue.firstOrNull()==step.second){
                    queue.removeFirst();removeNext(activity)
                }else {
                    val remaining=queue.size
                    queue.clear();launchingRemoval=false;lastScan=null
                    activity.alert("Удаление остановлено",if(result==Activity.RESULT_CANCELED)
                        "Удаление отменено. Осталось старых пакетов: $remaining."
                    else "Android не подтвердил удаление. Осталось старых пакетов: $remaining.")
                }
            }
        }
        return true
    }
}
