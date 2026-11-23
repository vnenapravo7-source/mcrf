package org.mcrf.client

import java.time.DayOfWeek
import java.util.Locale

internal object NetworkBehaviorPresentation {
    const val PROFILE_CONFLICTS="Применяется один профиль: первый подходящий в списке. При одинаковом сохранённом приоритете: точный Wi‑Fi, роуминг, любой Wi‑Fi, мобильная сеть. При полном совпадении порядок постоянный."
    const val SCHEDULE_CONFLICTS="Если совпали несколько заданий, выполняется первое включённое в списке. Пока оно ждёт разрешения или отключения VPN, остальные не запускаются."
    fun profiles(values:List<ConditionProfile>)=values.sortedWith(compareByDescending<ConditionProfile> {it.priority}
        .thenByDescending {when(it.condition){ProfileCondition.SSID->4;ProfileCondition.ROAMING->3;ProfileCondition.WIFI->2;ProfileCondition.CELLULAR->1}}
        .thenBy {it.id})
    fun rules(values:List<BehaviorSchedule.Rule>)=values.sortedWith(compareByDescending<BehaviorSchedule.Rule> {it.priority}.thenBy {it.id})
    fun time(minute:Int):String {
        require(minute in 0..1439)
        return String.format(Locale.ROOT,"%02d:%02d",minute/60,minute%60)
    }
    fun days(values:Set<DayOfWeek>):String = when(values) {
        DayOfWeek.values().toSet()->"Каждый день"
        DayOfWeek.values().take(5).toSet()->"По будням"
        setOf(DayOfWeek.SATURDAY,DayOfWeek.SUNDAY)->"По выходным"
        else->values.sortedBy {it.value}.joinToString(", ") {listOf("Пн","Вт","Ср","Чт","Пт","Сб","Вс")[it.value-1]}
    }
    fun window(value:BehaviorSchedule.Window):String = days(value.days)+" · "+
        if(value.startMinute==value.endMinute)"весь день"else time(value.startMinute)+"–"+time(value.endMinute)+if(value.startMinute>value.endMinute)" (+1 день)"else""
    fun schedule(value:BehaviorSchedule.Conditions):String =
        (if(value.windows.isEmpty())"В любое время"else value.windows.joinToString("\n",transform=::window))+"\n"+
            listOfNotNull(value.network.title,value.zoneId?.let {"Пояс: $it"},
                value.roaming?.let {if(it)"В роуминге"else"Без роуминга"},
                value.charging?.let {if(it)"На зарядке"else"Без зарядки"},
                value.batteryAtMost?.let {"Батарея ≤ $it%"}).joinToString(" · ")
}
