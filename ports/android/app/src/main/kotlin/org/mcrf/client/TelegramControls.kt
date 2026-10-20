package org.mcrf.client

import android.content.ActivityNotFoundException
import android.content.res.ColorStateList
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.Switch

class TelegramControls(private val activity:MainActivity,private val bridge:TelegramBridge):LinearLayout(activity) {
    private val info=activity.text("Выключен",10,Loft.MUTED).apply {setSingleLine(true);ellipsize=android.text.TextUtils.TruncateAt.END}
    private var updating=false
    private val toggle=Switch(activity).apply {
        contentDescription="Telegram WS · включить локальный SOCKS для Telegram";showText=false;setPadding(activity.dp(4),0,activity.dp(4),0)
        minimumWidth=activity.dp(48);minimumHeight=activity.dp(48)
        thumbTintList=ColorStateList.valueOf(Loft.INK)
        trackTintList=ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked),intArrayOf()),intArrayOf(Loft.ACCENT,Loft.LINE))
        setOnCheckedChangeListener {_,value ->if(!updating)try{bridge.setEnabled(value)}catch(_:Exception){update();activity.alert("Telegram WS","Не удалось изменить состояние сервиса.")}}
    }
    private val open=activity.iconButton("telegram","Открыть в Telegram").apply {tag="telegram-open"
        setOnClickListener {try{bridge.openTelegram()}catch(_:ActivityNotFoundException){activity.alert("Telegram","На телефоне не найден Telegram.")}catch(error:Exception){activity.toast(error.message?:"Сначала включи TG WS.")}}
    }
    init {
        tag="telegram-inline";orientation=HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(0,0,0,0)
        val labels=LinearLayout(activity).apply {orientation=VERTICAL;addView(activity.text("TG WS",13,Loft.INK));addView(info,LayoutParams(-1,-2).apply {topMargin=activity.dp(3)})}
        addView(labels,LayoutParams(0,-2,1f));addView(toggle,LayoutParams(activity.dp(52),activity.dp(48)));addView(open,LayoutParams(activity.dp(48),activity.dp(48)));update()
    }
    fun update(){updating=true;toggle.isChecked=bridge.state.wanted;updating=false;info.text=if(bridge.state.running&&bridge.state.transport.isNotEmpty())"${bridge.state.transport} · ${bridge.state.port}" else bridge.state.message}
}
