package org.mcrf.client

/** Counts measured host actions. Thread CPU excludes native/remote engine CPU and radio tail energy. */
object BatteryCostReport {
    enum class Action { PROBE, SUBSCRIPTION_UPDATE, COUNTER_SAMPLE, SWITCH }
    data class Measurement(val count: Long = 0, val wallMs: Long = 0, val cpuMs: Long = 0) {
        fun plus(wall: Long, cpu: Long) = Measurement(UsageQuotaPolicy.add(count, 1),
            UsageQuotaPolicy.add(wallMs, wall.coerceAtLeast(0)), UsageQuotaPolicy.add(cpuMs, cpu.coerceAtLeast(0)))
    }
    fun label(action:Action)=when(action){Action.PROBE->"Проверки серверов";Action.SUBSCRIPTION_UPDATE->"Обновления подписок";Action.COUNTER_SAMPLE->"Сбор статистики";Action.SWITCH->"Переключения серверов"}
    fun description(action: Action, value: Measurement): String =
        "${label(action)}: ${value.count} действий\nВремя ${value.wallMs} мс · CPU потока MCRF ${value.cpuMs} мс"
    data class Row(val action:String,val frequency:String,val impact:String)
    /** Count is not a rate: the ledger has no reliable duration for the whole collection window. */
    fun row(action:Action,value:Measurement)=Row(label(action),
        if(value.count==0L)"Нет запусков"else"${value.count} "+if(value.count%10 in 2L..4L&&value.count%100 !in 12L..14L)"раза"else"раз",
        if(value.count==0L)"Не измерено"else"CPU ${value.cpuMs} мс\nВремя ${value.wallMs} мс")
    const val LIMITATION = "Измерены действия и CPU вызывающего потока MCRF. CPU движков, радио и расход всего телефона не измерены; энергия в мА·ч недоступна. Любая оценка энергии — приблизительная."
}
