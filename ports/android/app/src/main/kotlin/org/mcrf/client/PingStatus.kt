package org.mcrf.client

/** A missing measurement is neutral, not an unreachable server. */
object PingStatus {
    enum class Tone { UNKNOWN, GOOD, HIGH, UNAVAILABLE }
    const val GOOD_LIMIT_MS = 100L
    /** Also show the fastest cluster as green when every route is above 100 ms. */
    fun goodLimit(delays: Iterable<Long?>): Long {
        val best=delays.filterNotNull().filter {it>=0}.minOrNull() ?: return GOOD_LIMIT_MS
        return maxOf(GOOD_LIMIT_MS,if(best>Long.MAX_VALUE-50)Long.MAX_VALUE else best+50)
    }
    /** Known successes first, unmeasured next, failures last. Ties keep their original order. */
    fun <T> sorted(values: List<T>, delay: (T) -> Long?): List<T> = values.sortedWith(
        compareBy<T> { when (val value = delay(it)) { null -> 1; else -> if (value < 0) 2 else 0 } }
            .thenBy { delay(it)?.takeIf { value -> value >= 0 } ?: 0L })
    @JvmStatic @JvmOverloads fun tone(delay: Long?, goodLimit: Long = GOOD_LIMIT_MS): Tone = when {
        delay == null -> Tone.UNKNOWN
        delay < 0 -> Tone.UNAVAILABLE
        delay <= goodLimit -> Tone.GOOD
        else -> Tone.HIGH
    }
    @JvmStatic fun label(delay: Long?): String = when (tone(delay)) {
        Tone.UNKNOWN -> "—"
        Tone.UNAVAILABLE -> "Нет связи"
        else -> "$delay мс"
    }
    @JvmStatic @JvmOverloads fun description(delay: Long?, goodLimit: Long = GOOD_LIMIT_MS): String = when (tone(delay,goodLimit)) {
        Tone.UNKNOWN -> "Задержка не проверена"
        Tone.UNAVAILABLE -> "Сервер не отвечает"
        Tone.GOOD -> "Задержка $delay мс · хорошая"
        Tone.HIGH -> "Задержка $delay мс · повышенная"
    }
}
