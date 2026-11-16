package org.mcrf.client

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors
import org.json.JSONObject

internal object RoutingConflictUi {
    private val worker=Executors.newSingleThreadExecutor {task->Thread(task,"mcrf-route-conflicts").apply {isDaemon=true}}
    fun button(activity:MainActivity,store:RoutingStore,changed:(JSONObject,String)->Unit={_,_->}):View=activity.button("Проверить конфликты правил",false).apply {
        tag="routing-conflict-check";minHeight=activity.dp(48);setOnClickListener {show(activity,store,changed)}
    }
    fun show(activity:MainActivity,store:RoutingStore,changed:(JSONObject,String)->Unit={_,_->}) {
        if(activity.isFinishing||activity.isDestroyed||activity.working())return
        if(ExperienceModeStore(activity).load()!=ExperienceMode.EXPERT){activity.alert("Конфликты правил","Доступно в экспертном режиме.");return}
        val body=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL;setPadding(activity.dp(20),activity.dp(12),activity.dp(20),activity.dp(12))}
        val status=activity.text("Проверяем текущую группу…",13,Loft.MUTED);body.addView(status)
        val dialog=ThemedDialogBuilder(activity).setTitle("Конфликты правил")
            .setView(ScrollView(activity).apply {addView(body)}).setNegativeButton("Ничего не менять",null).create()
        var job:java.util.concurrent.Future<*>?=null
        dialog.setOnDismissListener {job?.cancel(true)};dialog.show()
        job=worker.submit {
            try {
                val (revision,state)=store.presentationSnapshot();val generation=store.generation()
                val report=RoutingConflictAnalyzer.inspect(state,RoutingConflictEvidence(activity.applicationContext,state)::resolve)
                activity.runOnUiThread {
                    if(!dialog.isShowing||activity.isDestroyed||activity.isFinishing)return@runOnUiThread
                    if(store.presentationRevision()!=revision||store.generation()!=generation||ExperienceModeStore(activity).load()!=ExperienceMode.EXPERT) {
                        status.text="Настройки изменились. Закрой окно и повтори проверку.";return@runOnUiThread
                    }
                    store.conflicts=revision to report
                    highlight(activity.window.decorView,report)
                    status.text="Проверено правил: ${report.enabledRules}. Первое совпадение задаёт выход. Красный — конфликт, жёлтый — возможное пересечение."
                    if(report.truncated)body.addView(activity.text("Проверка частичная: достигнут лимит сложности или количества результатов. Остальные пары не объявляются бесконфликтными.",12,Loft.ERROR),activity.space(0,12))
                    if(report.findings.isEmpty())body.addView(activity.text(if(report.truncated)"В проверенной части пересечений не найдено."else"Пересечений включённых правил не найдено.",14,Loft.INK))
                    report.findings.forEach {finding->
                        body.addView(activity.card().apply {
                            addView(activity.text("№${finding.earlierPosition} ${finding.earlierName} ↔ №${finding.laterPosition} ${finding.laterName}",14,Loft.INK))
                            addView(activity.text("${if(finding.exact)"Подтверждено"else"Возможно"} · ${finding.explanation}",12,Loft.MUTED),activity.space(8,8))
                            addView(activity.button("Выбрать действие",false).apply {minHeight=activity.dp(48);setOnClickListener {choose(activity,store,report,finding,revision,dialog,changed)}})
                        },activity.space(0,12))
                    }
                }
            }catch(_:java.util.concurrent.CancellationException){}catch(_:Exception) {
                activity.runOnUiThread {if(dialog.isShowing&&!activity.isDestroyed)status.text="Не удалось завершить проверку. Правила не изменены."}
            }
        }
    }
    private fun choose(activity:MainActivity,store:RoutingStore,report:RoutingConflictAnalyzer.Report,finding:RoutingConflictAnalyzer.Finding,
        revision:String,reportDialog:AlertDialog,changed:(JSONObject,String)->Unit) {
        var selected=0
        val labels=arrayOf("Ничего не менять","Оставить №${finding.earlierPosition}, отключить №${finding.laterPosition}","Оставить №${finding.laterPosition}, отключить №${finding.earlierPosition}")
        val dialog=ThemedDialogBuilder(activity).setTitle("Выбери одно действие")
            .setSingleChoiceItems(labels,0){_,which->selected=which}.setNegativeButton("Отмена",null).setPositiveButton("Применить",null).create()
        dialog.setOnShowListener {
            val list=dialog.listView
            val warning=activity.text("Выбранное правило будет отключено целиком, включая трафик вне пересечения.",12,Loft.MUTED)
            warning.setPadding(activity.dp(20),activity.dp(8),activity.dp(20),activity.dp(8))
            // Keep radio rows unobscured; the explanation is announced before confirmation.
            list.contentDescription=warning.text
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply {minHeight=activity.dp(48);setOnClickListener {
                if(selected==0){dialog.dismiss();return@setOnClickListener}
                val winner=if(selected==1)finding.earlierId else finding.laterId
                val disabled=if(selected==1)finding.laterId else finding.earlierId
                ThemedDialogBuilder(activity).setTitle("Отключить правило целиком?").setMessage(warning.text)
                    .setNegativeButton("Ничего не менять",null).setPositiveButton("Отключить"){_,_->
                        RoutingUiMutation.submit(activity,store,this,{next->
                            require(ExperienceModeStore(activity).load()==ExperienceMode.EXPERT){"Режим изменился. Повтори проверку."}
                            val resolved=RoutingConflictAnalyzer.resolve(next,report,winner,setOf(disabled))
                            next.put("rules",resolved.getJSONArray("rules"))
                        },{next,savedRevision->changed(next,savedRevision);dialog.dismiss();reportDialog.dismiss();activity.toast("Правило отключено. Повтори проверку оставшихся пересечений.")},expectedRevision=revision)
                    }.show()
            }}
        };dialog.show()
    }
    fun highlight(root:View,report:RoutingConflictAnalyzer.Report?) {
        if(root is ViewGroup)for(i in 0 until root.childCount)highlight(root.getChildAt(i),report)
        val tag=root.tag as? String?:return
        if(!tag.startsWith("routing-rule:"))return
        val id=tag.removePrefix("routing-rule:")
        val findings=report?.findings.orEmpty().filter {it.earlierId==id||it.laterId==id}
        val hint=root.findViewWithTag<TextView>("routing-conflict:$id")
        hint?.visibility=if(findings.isEmpty())View.GONE else View.VISIBLE
        if(findings.isEmpty()){root.foreground=null;return}
        val exact=findings.any {it.exact&&!it.sameOutput}
        hint?.text="${if(exact)"Конфликт"else"Возможное пересечение"} · ${findings.size}. Проверка — рядом с заголовком «Правила»."
        root.foreground=GradientDrawable().apply {setColor(Color.TRANSPARENT);cornerRadius=16*root.resources.displayMetrics.density;setStroke((2*root.resources.displayMetrics.density).toInt().coerceAtLeast(1),if(exact)Loft.ERROR else 0xffc28a25.toInt())}
    }
}
