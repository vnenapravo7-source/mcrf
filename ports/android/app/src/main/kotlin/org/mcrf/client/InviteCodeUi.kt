package org.mcrf.client

import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

class InviteCodeUi(private val activity:MainActivity,private val install:(Set<String>)->Unit){
    private val worker=Executors.newSingleThreadExecutor {task->Thread(task,"mcrf-invite").apply {isDaemon=true}}
    private val busy=java.util.concurrent.atomic.AtomicBoolean()
    private var closed=false
    private var changed:()->Unit={activity.navigate(ClientSettingsNavigation.INVITE)}
    fun show() {
        val host=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL}
        var dialog:android.app.AlertDialog?=null
        fun allowInput(){dialog?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM or android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)}
        fun refresh(){host.removeAllViews();host.addView(view(inDialog=true));allowInput()}
        changed={if(!closed)refresh()};refresh()
        val created=ThemedDialogBuilder(activity,compactActions=true).setView(CompactUi.scroll(activity,host,.74f)).setNegativeButton("Закрыть",null).create()
        dialog=created
        created.setOnDismissListener {closed=true;worker.shutdown()};created.show();allowInput()
    }
    fun view(inDialog:Boolean=false,state:InviteCodeSync.State?=runCatching {InviteCodeSync.state(activity)}.getOrNull()):View {
        val box=(if(inDialog)LinearLayout(activity).apply {
            orientation=LinearLayout.VERTICAL;setPadding(activity.dp(20),activity.dp(16),activity.dp(20),activity.dp(12))
        }else activity.card()).apply {tag="invite-code-settings"}
        if(inDialog)box.addView(activity.text("Инвайт",22,Loft.INK).apply {tag="invite-dialog-title";setAccessibilityHeading(true)},activity.space(0,12))
        box.addView(activity.text(if(state==null)"Привязать подписку" else "Инвайт-код привязан",18,Loft.INK).apply {tag="invite-binding-title"})
        box.addView(activity.text("Код получает доступные сервера для VPN, обхода белых списков и исключает из маршрутизации российские приложения.",12,Loft.MUTED),activity.space(8,12))
        if(state==null){
            val code=activity.editor("6 цифр").apply {tag="invite-code-input";inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD;setSingleLine(true);gravity=Gravity.CENTER}
            box.addView(code)
            box.addView(activity.button("Привязать",true).apply {tag="invite-bind";StandardButtons.compact(activity,this,primary=true);setOnClickListener {val entered=code.text.toString();this@InviteCodeUi.run("Привязка",{InviteCodeSync.redeem(activity,entered)}) {
                val missing=EnginePackages.missing(activity,InviteCodeSync.requiredEngines(activity))
                if(missing.isNotEmpty())install(missing)
            }}},activity.space(10,0))
        }else{
            val whenText=if(state.lastSync>0)DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(Date(state.lastSync)) else "ещё не выполнялась"
            if(!state.subscriptionAvailable)box.addView(activity.text("VPN-подписка для этого пользователя не задана. Серверы обхода импортированы и продолжат обновляться.",12,Loft.ERROR),activity.space(0,10))
            if(EnginePackages.missing(activity,InviteCodeSync.requiredEngines(activity)).isNotEmpty())box.addView(activity.text("Для части серверов нужны движки из раздела «Обновления и движки».",12,Loft.MUTED),activity.space(8,0))
            val actionHeight=activity.dp(48)
            val actions=LinearLayout(activity).apply {gravity=Gravity.CENTER_VERTICAL
                fun action(label:String,primary:Boolean,click:()->Unit)=activity.button(label,primary).apply {
                    StandardButtons.compact(activity,this,primary=primary)
                    tag=if(label=="Обновить")"invite-refresh"else"invite-unlink"
                    gravity=Gravity.CENTER;minHeight=actionHeight;maxLines=2
                    setPadding(activity.dp(8),activity.dp(4),activity.dp(8),activity.dp(4))
                    setOnClickListener {click()}
                }
                addView(action("Обновить",true){this@InviteCodeUi.run("Синхронизация",{InviteCodeSync.refreshIfLinked(activity)?:0})},LinearLayout.LayoutParams(0,actionHeight,1f).apply {rightMargin=activity.dp(4)})
                addView(action("Отвязать",false){this@InviteCodeUi.run("Отвязка",{InviteCodeSync.unlink(activity);0})},LinearLayout.LayoutParams(0,actionHeight,1f).apply {leftMargin=activity.dp(4)})}
            box.addView(actions,activity.space(12,0))
            box.addView(activity.text("Последняя успешная синхронизация: $whenText",12,if(Loft.dark)0xff80c9a5.toInt()else 0xff156749.toInt()).apply {
                tag="invite-last-sync";accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE
            },activity.space(10,0))
        }
        if(state!=null)box.addView(activity.text("Обновляется по общему расписанию подписок.",12,Loft.MUTED),activity.space(12,0))
        return box
    }
    private fun run(label:String,action:()->Int,afterSuccess:()->Unit={}){
        if(!busy.compareAndSet(false,true))return
        worker.execute {try{action();activity.runOnUiThread {if(!closed&&!activity.isDestroyed&&!activity.isFinishing){activity.toast("$label завершена");changed();afterSuccess()}}}catch(error:Exception){activity.runOnUiThread {if(!closed&&!activity.isDestroyed&&!activity.isFinishing){if(runCatching {InviteCodeSync.state(activity)}.getOrNull()!=null)changed();activity.alert(label,ClientEvents.redact(error.message))}}}finally{busy.set(false)}}
    }
}
