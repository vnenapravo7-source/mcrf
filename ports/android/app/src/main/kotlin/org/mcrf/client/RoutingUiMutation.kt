package org.mcrf.client

import android.view.View
import java.util.WeakHashMap
import java.util.concurrent.Executors
import org.json.JSONObject

/** Local write path: no MainActivity.run(), whose error path rebuilds the page. */
internal object RoutingUiMutation {
    private val worker=Executors.newSingleThreadExecutor {task->Thread(task,"mcrf-routing-edit").apply {isDaemon=true}}
    private val pending=WeakHashMap<MainActivity,Boolean>() // UI-thread access only
    fun submit(activity:MainActivity,store:RoutingStore,anchor:View,edit:(JSONObject)->Unit,
        success:(JSONObject,String)->Unit={_,_->},failed:()->Unit={},expectedRevision:String=store.presentationRevision()) {
        if(activity.isFinishing||activity.isDestroyed||activity.working()||pending[activity]==true){failed();return}
        val generation=store.generation()
        pending[activity]=true;anchor.isEnabled=false
        worker.execute {
            try {
                val (next,revision)=store.editAtRevision(expectedRevision,generation,edit)
                activity.runOnUiThread {
                    pending.remove(activity);anchor.isEnabled=true
                    if(!activity.isDestroyed&&!activity.isFinishing) {
                        if(store.presentationRevision()!=revision||store.generation()!=generation){failed();activity.toast("Маршруты изменились. Открой раздел заново.");return@runOnUiThread}
                        RoutingConflictUi.highlight(activity.window.decorView,null)
                        success(next,revision)
                    }
                }
            } catch(error:Exception) {
                activity.runOnUiThread {
                    pending.remove(activity);anchor.isEnabled=true;failed()
                    if(!activity.isDestroyed&&!activity.isFinishing)activity.alert("Маршруты",if(error is IllegalArgumentException)error.message.orEmpty()else"Не удалось сохранить. Предыдущие настройки сохранены; открой раздел заново перед повтором.")
                }
            }
        }
    }
}
