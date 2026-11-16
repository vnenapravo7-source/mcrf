package org.mcrf.client

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import java.lang.ref.WeakReference
import java.util.concurrent.Executors

/** No profile writes and no silent uninstall. Each step is a separate Android consent. */
internal object BsEngineMigration {
    private val worker=Executors.newSingleThreadExecutor()
    private var owner=WeakReference<MainActivity>(null)
    private var queue=ArrayDeque<String>()
    private var pending:Pair<Int,String>?=null
    private var checking=false
    internal fun advance(confirmed:Boolean,absent:Boolean,verified:Boolean)=confirmed&&absent&&verified
    internal fun removable(kinds:Iterable<String>)=kinds.filter{it in BsEngineContract.engines}
    fun offer(activity:MainActivity) {
        if(checking||pending!=null||activity.active()||ProbeCoordinator.measurements!=null)return
        checking=true
        worker.execute {
            val legacy=try {
                BsEnginePackage.installation(activity)
                removable(BsEngineContract.engines).filter {kind->try{activity.packageManager.getApplicationInfo(EngineModules.packageName(kind),0);true}catch(_:PackageManager.NameNotFoundException){false}}
            }catch(_:Exception){emptyList()}
            activity.runOnUiThread {
                checking=false
                if(activity.isFinishing||activity.isDestroyed||legacy.isEmpty())return@runOnUiThread
                ThemedDialogBuilder(activity).setTitle("BS Engine проверен")
                    .setMessage("Старые пакеты CSQTT и QWDTT можно удалить. Подключения сохранятся. WDTT остаётся отдельным движком. Android запросит подтверждение каждого удаления; отмена остановит очистку.")
                    .setNegativeButton("Оставить",null).setPositiveButton("Удалить старые"){_,_->
                        owner=WeakReference(activity);queue=ArrayDeque(legacy);next(activity)
                    }.show()
            }
        }
    }
    private fun next(activity:MainActivity) {
        if(queue.isEmpty()||activity.active()||ProbeCoordinator.measurements!=null||activity.isFinishing||activity.isDestroyed()){queue.clear();return}
        val kind=queue.removeFirst()
        worker.execute {
            val verified=runCatching{BsEnginePackage.installation(activity);true}.getOrDefault(false)
            activity.runOnUiThread {
                if(!verified||activity.active()||ProbeCoordinator.measurements!=null||activity.isFinishing||activity.isDestroyed()){queue.clear();return@runOnUiThread}
                val lease=EngineUpdateGate.beginMutation(activity,"bs_migration","remove",kind)?:run{queue.clear();return@runOnUiThread}
                pending=lease.pending.requestCode to kind
                try {
                    @Suppress("DEPRECATION") val intent=Intent(Intent.ACTION_UNINSTALL_PACKAGE,Uri.parse("package:${EngineModules.packageName(kind)}"))
                        .putExtra(Intent.EXTRA_RETURN_RESULT,true)
                    activity.startActivityForResult(intent,lease.pending.requestCode)
                }catch(_:Exception){EngineUpdateGate.complete(activity,lease.pending.token){true};pending=null;queue.clear()}
            }
        }
    }
    fun returned(activity:MainActivity,request:Int,result:Int):Boolean {
        val step=pending?.takeIf{it.first==request}?:return false
        pending=null
        worker.execute {
            runCatching{EngineUpdates.applyInstallerResult(activity,request,result)}
            val absent=try{activity.packageManager.getApplicationInfo(EngineModules.packageName(step.second),0);false}catch(_:PackageManager.NameNotFoundException){true}
            val verified=runCatching{BsEnginePackage.installation(activity);true}.getOrDefault(false)
            activity.runOnUiThread {
                if(owner.get()!==activity||!advance(result==Activity.RESULT_OK,absent,verified))queue.clear()else next(activity)
            }
        }
        return true
    }
}
