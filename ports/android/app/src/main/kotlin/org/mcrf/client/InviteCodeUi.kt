package org.mcrf.client

import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.Spinner
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

class InviteCodeUi(private val activity:MainActivity,private val install:(Set<String>)->Unit){
    private val worker=Executors.newSingleThreadExecutor()
    fun view():View {
        val box=activity.card().apply {tag="invite-code-settings"}
        val state=runCatching {InviteCodeSync.state(activity)}.getOrNull()
        box.addView(activity.text(if(state==null)"Привязать подписку" else "Инвайт-код привязан",18,Loft.INK))
        box.addView(activity.text("Код получает доступные серверы обхода и, если она задана, вашу VPN-подписку: ${InviteCodeSync.groupSummary()}. Сам код на телефоне не сохраняется.",12,Loft.MUTED),activity.space(8,12))
        if(state==null){
            val code=activity.editor("6 цифр").apply {tag="invite-code-input";inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD;setSingleLine(true);gravity=Gravity.CENTER}
            box.addView(code)
            box.addView(activity.button("Привязать и синхронизировать",true).apply {setOnClickListener {this@InviteCodeUi.run("Привязка",{InviteCodeSync.redeem(activity,code.text.toString())}) {
                val missing=EnginePackages.missing(activity,InviteCodeSync.requiredEngines())
                if(missing.isNotEmpty())install(missing)
            }}},activity.space(10,0))
        }else{
            val whenText=if(state.lastSync>0)DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(Date(state.lastSync)) else "ещё не выполнялась"
            box.addView(activity.text("Последняя успешная синхронизация: $whenText · подключений: ${state.lastCount}",12,Loft.MUTED),activity.space(0,10))
            if(!state.subscriptionAvailable)box.addView(activity.text("VPN-подписка для этого пользователя не задана. Серверы обхода импортированы и продолжат обновляться.",12,Loft.ERROR),activity.space(0,10))
            val missing=EnginePackages.missing(activity,InviteCodeSync.requiredEngines())
            if(missing.isNotEmpty())box.addView(activity.button("Установить ${missing.size} движка",true).apply {setOnClickListener {install(missing)}},activity.space(10,0))
            val actions=LinearLayout(activity).apply {gravity=Gravity.CENTER_VERTICAL
                addView(activity.button("Обновить сейчас",true).apply {setOnClickListener {this@InviteCodeUi.run("Синхронизация",{InviteCodeSync.refreshIfLinked(activity)?:0})}},LinearLayout.LayoutParams(0,-2,1f).apply {rightMargin=activity.dp(4)})
                addView(activity.button("Отвязать",false).apply {setOnClickListener {InviteCodeSync.unlink(activity);activity.navigate(ClientSettingsNavigation.INVITE)}},LinearLayout.LayoutParams(0,-2,1f).apply {leftMargin=activity.dp(4)})}
            box.addView(actions)
        }
        val options=SubscriptionOptionsStore.load(activity)
        box.addView(activity.text("Автообновление использует общий интервал подписок",13,Loft.INK),activity.space(16,6))
        val choices=SubscriptionSchedule.labels
        val spinner=Spinner(activity).apply {adapter=android.widget.ArrayAdapter(activity,android.R.layout.simple_spinner_dropdown_item,choices);setSelection(SubscriptionSchedule.hours.indexOf(options.intervalMinutes/60).coerceAtLeast(0));tag="invite-refresh-interval"}
        box.addView(spinner)
        box.addView(activity.button("Сохранить интервал",false).apply {setOnClickListener {SubscriptionUpdates.setInterval(activity,SubscriptionSchedule.hours[spinner.selectedItemPosition]*60);activity.toast("Интервал сохранён")}},activity.space(8,0))
        return box
    }
    private fun run(label:String,action:()->Int,afterSuccess:()->Unit={}){
        worker.execute {try{val count=action();activity.runOnUiThread {activity.toast("$label завершена · $count подключений");activity.navigate(ClientSettingsNavigation.INVITE);afterSuccess()}}catch(error:Exception){activity.runOnUiThread {activity.alert(label,ClientEvents.redact(error.message))}}}
    }
}
