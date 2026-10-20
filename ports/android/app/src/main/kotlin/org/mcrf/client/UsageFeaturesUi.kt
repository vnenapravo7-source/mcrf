package org.mcrf.client

import android.app.AlertDialog
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView

object UsageFeaturesUi {
    fun selected(activity:MainActivity)=UsageFeatures.decode(activity.prefs.getString(UsageFeatures.KEY,null))
    private fun form(activity:MainActivity,initial:Set<UsageFeatures.Feature>,save:(Set<UsageFeatures.Feature>)->Unit):LinearLayout=with(activity) {
        val chosen=initial.toMutableSet()
        LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL;tag="usage-features-form"
            addView(text("Можно выбрать несколько. Ненужные настройки скроются; серверы, сохранённые значения и работающие соединения останутся.",12,Loft.MUTED),space(0,12))
            UsageFeatures.Feature.values().forEach {feature->
                addView(CheckBox(this@with).apply {
                    text=feature.title;tag="usage-feature-${feature.id}"
                    textSize=14f;setTextColor(Loft.INK);minHeight=dp(48)
                    buttonTintList=android.content.res.ColorStateList.valueOf(Loft.ACCENT)
                    isChecked=feature in chosen
                    setOnCheckedChangeListener {_,checked->if(checked)chosen.add(feature)else chosen.remove(feature)}
                },space(0,0))
                addView(text(feature.detail,12,Loft.MUTED).apply {setPadding(dp(12),0,dp(8),0)},space(0,8))
            }
            addView(button("Применить",false).apply {tag="usage-features-save";setOnClickListener {
                try {save(chosen.toSet())}catch(error:Exception){alert("Что использовать",error.message?:"Не удалось сохранить выбор.")}
            }},space(8,0))
        }
    }
    private fun save(activity:MainActivity,chosen:Set<UsageFeatures.Feature>,saved:()->Unit) {
        val encoded=UsageFeatures.encode(chosen)
        if(activity.working())return
        var committed=false
        activity.run(MainActivity.Operation {
            check(activity.prefs.edit().putString(UsageFeatures.KEY,encoded).putBoolean(UsageFeatures.ASKED,true).commit()) {"Не удалось сохранить выбор."}
            committed=true;""
        },false,Runnable {
            if(committed){
                // UI caches are invalidated on the UI thread, not the preference writer.
                activity.routingScreenCache.clear()
                saved()
            }
        })
    }
    fun settings(activity:MainActivity):LinearLayout=with(activity) {
        card().apply {
            tag="usage-features-settings"
            addView(text("Минимализм",18,Loft.INK),space(0,12))
            addView(form(this@with,selected(this@with)){chosen->save(this@with,chosen){render()}})
        }
    }
    fun firstLaunch(activity:MainActivity,done:()->Unit):AlertDialog {
        var dialog:AlertDialog?=null
        val content=form(activity,setOf(UsageFeatures.Feature.VPN)){chosen->save(activity,chosen){dialog?.dismiss();activity.render();done()}}
        content.setPadding(activity.dp(20),activity.dp(8),activity.dp(20),activity.dp(12))
        dialog=ThemedDialogBuilder(activity).setTitle("Чем будешь пользоваться?")
            .setView(ScrollView(activity).apply {addView(content)})
            .setNegativeButton("Позже",null).setCancelable(false).create()
        dialog.show()
        // Keep the dialog owned until the writer completes; Android's default negative
        // button would dismiss it even when another save is already in flight.
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            if(!activity.working()){
                var committed=false
                activity.run(MainActivity.Operation {
                    check(activity.prefs.edit().putBoolean(UsageFeatures.ASKED,true).commit()) {"Не удалось сохранить выбор."}
                    committed=true;""
                },false,Runnable {if(committed){dialog?.dismiss();done()}})
            }
        }
        return dialog
    }
}
