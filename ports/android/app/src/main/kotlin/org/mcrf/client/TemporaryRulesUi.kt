package org.mcrf.client

import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.ScrollView
import org.json.JSONObject
import java.util.concurrent.Executors

internal object TemporaryRulesUi {
    private val worker=Executors.newSingleThreadExecutor {r->Thread(r,"mcrf-temporary-rules-ui").apply {isDaemon=true}}
    fun show(activity:MainActivity,store:RoutingStore):Unit=with(activity) {
        if(ExperienceModeStore(this).load()!=ExperienceMode.EXPERT)return@with
        val body=EditorLayout.body(this)
        body.addView(text("Действуют до конца таймера, выше постоянных правил. В фоне Android может задержать таймер.",13,Loft.MUTED),space(0,8))
        val list=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL};body.addView(list)
        val handler=Handler(Looper.getMainLooper())
        val dialog=ThemedDialogBuilder(this).setTitle("Временные правила").setView(CompactUi.scroll(this,body))
            .setNegativeButton("Закрыть",null).setPositiveButton("Создать",null).create()
        var rows=emptyList<JSONObject>()
        var revision="";var generation=""
        var pending=false
        val timers=mutableListOf<Pair<JSONObject,android.widget.TextView>>()
        var rendered=false
        fun render() {
            list.removeAllViews()
            val now=TemporaryRulesRuntime.clock(activity)
            val active=rows.filter {!TemporaryRules.expired(it,now)}
            if(active.isEmpty())list.addView(text("Временных правил пока нет",13,Loft.MUTED),space(8,8))
            for(row in active)list.addView(LinearLayout(activity).apply {
                gravity=android.view.Gravity.CENTER_VERTICAL;isBaselineAligned=false
                val left=minOf(row.getLong("until")-now.wall,if(row.getInt("boot")==now.boot)row.getLong("elapsedUntil")-now.elapsed else Long.MAX_VALUE).coerceAtLeast(0)/1000
                addView(text("${row.getJSONObject("rule").optString("name")}\nОсталось ${left/60}:${(left%60).toString().padStart(2,'0')}",13,Loft.INK).also {timers.add(row to it)},LinearLayout.LayoutParams(0,-2,1f))
                addView(iconButton("trash","Удалить временное правило").apply {setOnClickListener {
                    if(pending)return@setOnClickListener
                    pending=true
                    worker.execute {
                        try {
                            store.temporaryChange(revision,generation){root,_->TemporaryRules.stop(root,row.getString("id"))}
                            runOnUiThread {if(dialog.isShowing&&!isDestroyed){dialog.dismiss();show(activity,store)}}
                        }catch(e:Exception){runOnUiThread {pending=false;if(dialog.isShowing)alert("Временные правила",e.message?:"Откройте список заново.")}}
                    }
                }},LinearLayout.LayoutParams(dp(48),dp(48)))
            },space(8,8))
        }
        val tick=object:Runnable {override fun run(){if(dialog.isShowing&&!isDestroyed){
            if(!rendered){render();rendered=true}else {
                val now=TemporaryRulesRuntime.clock(activity)
                for((row,label) in timers) {
                    val left=minOf(row.getLong("until")-now.wall,if(row.getInt("boot")==now.boot)row.getLong("elapsedUntil")-now.elapsed else Long.MAX_VALUE).coerceAtLeast(0)/1000
                    label.text="${row.getJSONObject("rule").optString("name")}\n"+if(TemporaryRules.expired(row,now))"Срок истёк"else"Осталось ${left/60}:${(left%60).toString().padStart(2,'0')}"
                }
            }
            handler.postDelayed(this,1000)
        }}}
        dialog.setOnDismissListener {handler.removeCallbacks(tick)}
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).apply {isEnabled=false;setOnClickListener {create(activity,store){
                if(dialog.isShowing&&!isDestroyed){dialog.dismiss();show(activity,store)}
            }}}
            list.addView(text("Загрузка…",13,Loft.MUTED))
            worker.execute {
                try {
                    val captured=store.presentationSnapshot();val state=captured.second
                    val capturedGeneration=store.generation();val capturedGroup=RoutingGroups.active(state)
                    val capturedRows=TemporaryRules.active(state,"expert",capturedGroup,TemporaryRulesRuntime.clock(activity))
                    runOnUiThread {if(dialog.isShowing&&!isDestroyed){
                        revision=captured.first;generation=capturedGeneration
                        rows=capturedRows;dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).isEnabled=true;handler.post(tick)
                    }}
                }catch(_:Exception){runOnUiThread {if(dialog.isShowing){list.removeAllViews();list.addView(text("Не удалось прочитать временные правила. Откройте список заново.",13,Loft.ERROR))}}}
            }
        }
        dialog.show()
    }
    private fun create(activity:MainActivity,store:RoutingStore,saved:()->Unit) {
        ThemedDialogBuilder(activity).setTitle("Срок правила").setItems(arrayOf("5 минут","15 минут","30 минут","1 час")){_,index->
            val captured=try {store.presentationSnapshot() to store.generation()}catch(_:Exception){
                activity.alert("Временные правила","Не удалось прочитать маршруты. Откройте список заново.");return@setItems
            }
            val state=captured.first.second;val revision=captured.first.first;val generation=captured.second
            val group=RoutingGroups.active(state)
            val originalIds=state.getJSONArray("rules").let {a->(0 until a.length()).map {a.getJSONObject(it).getString("id")}.toSet()}
            RoutingPage(activity,store,draftSaved={edited->
                require(ExperienceModeStore(activity).load()==ExperienceMode.EXPERT&&RoutingGroups.active(edited)==group){"Группа или режим изменились. Повторите создание."}
                require(edited.getString("defaultTarget")==state.getString("defaultTarget")){"Временное правило не меняет выход остального трафика."}
                val rules=edited.getJSONArray("rules")
                val chosen=(0 until rules.length()).map {rules.getJSONObject(it)}.single {it.getString("id") !in originalIds}
                store.temporaryChange(revision,generation){root,now->TemporaryRules.start(root,"expert",group,chosen,listOf(5,15,30,60)[index],now)}
                activity.runOnUiThread {if(!activity.isDestroyed&&!activity.isFinishing)saved()}
            }).edit(null)
        }.setNegativeButton("Отмена",null).show()
    }
}
