package org.mcrf.client

/** Canonical retained Beta diagnostics; temporary rules stay outside this registry. */
object BetaFeatureRegistry {
    data class Entry(val id:String,val title:String,val detail:String,val policy:AdaptivePolicyUi.Section?=null)
    val entries=listOf(
        Entry("quotas","Контроль расхода трафика","Лимиты и действия при достижении расхода.",AdaptivePolicyUi.Section.QUOTAS),
        Entry("battery","Что использует батарею?","Измерения действий и факторы фонового расхода.",AdaptivePolicyUi.Section.BATTERY)
    )
}
