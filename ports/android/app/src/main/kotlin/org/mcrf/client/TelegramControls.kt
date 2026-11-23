package org.mcrf.client

import android.content.ActivityNotFoundException
import android.view.Gravity
import android.widget.LinearLayout

class TelegramControls(private val activity:MainActivity,private val bridge:TelegramBridge):LinearLayout(activity) {
    private val info=activity.text("Выключен",10,Loft.MUTED).apply {setSingleLine(true);ellipsize=android.text.TextUtils.TruncateAt.END}
    private val toggle=ToggleConnectionButton(activity, {
        try {
            bridge.setEnabled(!bridge.state.wanted)
        } catch (_: Exception) {
            update()
            activity.alert("Telegram WS","Не удалось изменить состояние сервиса.")
        }
    }, ToggleConnectionButton.selectedStyle(activity)).apply {
        tag="telegram-inline-toggle"
        minimumHeight=activity.dp(ToggleConnectionButton.COMPACT_HEIGHT_DP)
    }
    private val open=activity.iconButton("telegram","Добавить в TG Proxy").apply {tag="telegram-open"
        setOnClickListener {try{bridge.openTelegram()}catch(_:ActivityNotFoundException){activity.alert("Telegram","На телефоне не найден Telegram.")}catch(error:Exception){activity.toast(error.message?:"Сначала включи TG WS.")}}
    }
    init {
        tag="telegram-inline";orientation=HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(0,0,0,0)
        val labels=LinearLayout(activity).apply {orientation=VERTICAL;addView(activity.text("TG WS",13,Loft.INK));addView(info,LayoutParams(-1,-2).apply {topMargin=activity.dp(3)})}
        addView(labels,LayoutParams(0,-2,1f))
        addView(toggle,LayoutParams(activity.dp(ToggleConnectionButton.COMPACT_WIDTH_DP),activity.dp(ToggleConnectionButton.COMPACT_HEIGHT_DP)))
        addView(open,LayoutParams(activity.dp(48),activity.dp(48)));update()
    }
    fun update() {
        val state = bridge.state
        toggle.state(state.wanted, false, true, state.wanted, false,
            "Telegram WS · ${if(state.wanted)"выключить" else "включить"} локальный SOCKS для Telegram")
        info.text=if(state.running&&state.transport.isNotEmpty())"${state.transport} · ${state.port}" else state.message
    }
}
