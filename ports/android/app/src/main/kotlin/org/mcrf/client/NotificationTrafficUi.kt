package org.mcrf.client

import android.view.View
import android.widget.LinearLayout
import android.widget.Switch

internal object NotificationTrafficUi {
    fun view(activity:MainActivity)=LinearLayout(activity).apply {
        orientation=LinearLayout.VERTICAL;tag="notification-traffic-settings"
        val settings=NotificationTrafficSettings.load(activity.prefs)
        addView(activity.text("Статистика в уведомлении",18,Loft.INK),activity.space(0,8))
        val intervals=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL;visibility=if(settings.enabled)View.VISIBLE else View.GONE}
        addView(Switch(activity).apply {
            tag="notification-traffic-enabled";text="Счётчики в уведомлении";activity.tune(this);isChecked=settings.enabled
            setOnCheckedChangeListener {_,checked->activity.prefs.edit().putBoolean(NotificationTrafficSettings.ENABLED,checked).apply();intervals.visibility=if(checked)View.VISIBLE else View.GONE}
        },LinearLayout.LayoutParams(-1,activity.dp(48)))
        fun interval(key:String,label:String,current:Int){
            val values=(listOf(1,2,3,5,10,15,20,30,45,60,120,300)+current).distinct().sorted()
            val help="Частота обновления скорости и объёма трафика в уведомлении. Меньший интервал увеличивает расход батареи. Не влияет на проверку соединения и автоопределение БС."
            val control=CarrierOption(activity,values.map {CarrierOption.Value("$it с",help)},values.indexOf(current),{})
            control.tag=key;control.describe(label,help)
            control.onSelectionChanged={index->activity.prefs.edit().putInt(key,values[index]).apply()}
            intervals.addView(control,LinearLayout.LayoutParams(-1,-2))
        }
        interval(NotificationTrafficSettings.FOREGROUND,"Приложение открыто",settings.foregroundSeconds)
        interval(NotificationTrafficSettings.BACKGROUND,"В фоне",settings.backgroundSeconds)
        addView(intervals)
        addView(activity.text("Частое обновление увеличивает расход батареи. При выключенном экране опрос остановлен; после пробуждения данные обновляются. Без счётчиков — «Подключено к серверу».",12,Loft.MUTED),activity.space(8,0))
    }
}
