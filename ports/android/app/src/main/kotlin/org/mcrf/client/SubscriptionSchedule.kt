package org.mcrf.client

/** Shared by the picker, startup and backup validation. Zero is an explicit manual choice. */
object SubscriptionSchedule {
    const val DEFAULT_HOURS = 6
    val hours = listOf(0, 1, 3, 6, 12, 24, 72, 168)
    val labels = listOf("Вручную", "Раз в час", "Раз в 3 часа", "Раз в 6 часов", "Раз в 12 часов", "Раз в день", "Раз в 3 дня", "Раз в неделю")
    fun selected(saved: Int?) = saved?.takeIf { it in hours } ?: DEFAULT_HOURS
    const val MIN_MINUTES = 30
    fun validateMinutes(minutes: Int) { require(minutes == 0 || minutes >= MIN_MINUTES) { "Интервал — не меньше 30 минут; 0 означает вручную." } }
    fun badgeMinutes(minutes: Int): String { validateMinutes(minutes); return when { minutes == 0 -> "off"; minutes % 1440 == 0 -> "${minutes / 1440}d"; minutes % 60 == 0 -> "${minutes / 60}h"; else -> "${minutes}m" } }
    fun badge(hours: Int) = when {
        hours == 0 -> "off"
        hours < 24 -> "${hours}h"
        else -> "${hours / 24}d"
    }
}
