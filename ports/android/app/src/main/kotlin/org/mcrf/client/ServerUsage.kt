package org.mcrf.client

import android.content.Context
import android.os.SystemClock
import java.time.LocalDate

/** Aggregate payload counters. Mixed and switching routes use a separate unattributed bucket. */
internal object ServerUsage {
    const val UNATTRIBUTED = "unattributed"
    internal const val ENABLED = "enabled"
    private const val LIMIT = 512
    private const val FLUSH_MS = 10_000L
    private const val HISTORY_LIMIT = 4096
    internal const val PERIOD_COUNT = 20
    private const val HISTORY_DAYS = PERIOD_COUNT * 7
    private var revision = 0L
    private val pending = LinkedHashMap<String, Totals>()
    private val pendingHistory = LinkedHashMap<DailyKey, Totals>()
    private var lastPrunedDay = Long.MIN_VALUE
    private var lastFlush = 0L
    internal fun preferences(context: Context) = context.getSharedPreferences("server-usage", Context.MODE_PRIVATE)
    fun enabled(context: Context) = preferences(context).getBoolean(ENABLED, true)

    @Synchronized fun setEnabled(context: Context, enabled: Boolean) {
        if (enabled(context) == enabled) return
        flush(context)
        revision++
        preferences(context).edit().putBoolean(ENABLED, enabled).apply()
    }

    @Synchronized fun token(context: Context): Long? = revision.takeIf { enabled(context) }

    data class Totals(val sent: Long = 0, val received: Long = 0, val sessions: Long = 0) {
        val bytes: Long get() = add(sent, received)
        operator fun plus(other: Totals) = Totals(add(sent, other.sent), add(received, other.received), add(sessions, other.sessions))
    }

    private data class DailyKey(val day: Long, val server: String) {
        val preference: String get() = "day:$day:$server"
    }

    data class Period(val start: LocalDate, val end: LocalDate, val totals: Totals)

    @Synchronized fun history(context: Context, server: String, weekly: Boolean): List<Period> {
        val store = preferences(context)
        val today = LocalDate.now().toEpochDay()
        return periods(today, weekly) { day ->
            val key = DailyKey(day, server)
            parse(store.getString(key.preference, null)) + (pendingHistory[key] ?: Totals())
        }
    }

    internal fun periods(today: Long, weekly: Boolean, dayTotals: (Long) -> Totals): List<Period> {
        val days = if (weekly) 7 else 1
        return (PERIOD_COUNT - 1 downTo 0).map { offset ->
            val end = today - offset * days
            val start = end - days + 1
            val totals = (start..end).fold(Totals()) { sum, day -> sum + dayTotals(day) }
            Period(LocalDate.ofEpochDay(start), LocalDate.ofEpochDay(end), totals)
        }
    }

    data class Counters(val sent: Long, val received: Long) {
        fun delta(previous: Counters?) = Counters(
            (sent - (previous?.sent ?: 0L)).coerceAtLeast(0L),
            (received - (previous?.received ?: 0L)).coerceAtLeast(0L)
        )

        fun highWater(previous: Counters?) = Counters(
            maxOf(sent, previous?.sent ?: 0L), maxOf(received, previous?.received ?: 0L)
        )
    }

    /** One cursor per VPN service instance. All callers serialize through the same executor. */
    class Session(private val context: Context) {
        private var sampleGeneration = -1L
        private var sampleToken = token(context)
        private var observed: Counters? = null
        private var connection: Triple<Long, String, Long>? = null

        fun sample(generation: Long, server: String?, tx: Long, rx: Long, token: Long) {
            if (tx < 0 || rx < 0 || generation < sampleGeneration) return
            val current = Counters(tx, rx)
            if (token != sampleToken) {
                sampleToken = token
                observed = current // Collection changed during an existing run. Establish a fresh baseline.
                sampleGeneration = generation
                return
            }
            if (generation != sampleGeneration) {
                observed = null
                sampleGeneration = generation
            }
            val delta = current.delta(observed)
            observed = current.highWater(observed)
            if (delta.sent != 0L || delta.received != 0L) {
                record(context, server ?: UNATTRIBUTED, Totals(sent = delta.sent, received = delta.received), token)
            }
        }

        fun connected(generation: Long, server: String, token: Long) {
            val next = Triple(generation, server, token)
            if (connection == next) return
            connection = next
            record(context, server, Totals(sessions = 1), token)
        }
    }

    private fun parse(value: String?): Totals {
        val fields = value?.split(',').orEmpty()
        if (fields.size != 3) return Totals()
        fun field(index: Int) = fields[index].toLongOrNull()?.coerceAtLeast(0L) ?: 0L
        return Totals(field(0), field(1), field(2))
    }

    @Synchronized fun totals(context: Context): Map<String, Totals> {
        val totals = preferences(context).all.entries.asSequence().filter { it.key.startsWith("row:") }
            .associate { it.key.removePrefix("row:") to parse(it.value as? String) }.toMutableMap()
        pending.forEach { (key, value) -> totals[key] = (totals[key] ?: Totals()) + value }
        return totals
    }

    @Synchronized fun clear(context: Context) {
        revision++
        pending.clear()
        pendingHistory.clear()
        lastPrunedDay = Long.MIN_VALUE
        val store = preferences(context)
        store.edit().clear().putBoolean(ENABLED, store.getBoolean(ENABLED, false)).apply()
    }

    @Synchronized fun flush(context: Context) {
        if (pending.isEmpty()) return
        val store = preferences(context)
        val editor = store.edit()
        pending.forEach { (key, delta) ->
            val name = "row:$key"
            val total = parse(store.getString(name, null)) + delta
            editor.putString(name, "${total.sent},${total.received},${total.sessions}")
        }
        val today = LocalDate.now().toEpochDay()
        val storedHistory = store.all.keys.filter { it.startsWith("day:") }
        val expired = if (lastPrunedDay != today) storedHistory.filter { dayOf(it) <= today - HISTORY_DAYS }.toSet()
            else emptySet()
        val candidates = pendingHistory.keys.map(DailyKey::preference)
        val keys = (storedHistory - expired + candidates).distinct()
        val removed = keys.sortedBy(::dayOf).take((keys.size - HISTORY_LIMIT).coerceAtLeast(0)).toSet()
        (expired + removed).forEach(editor::remove)
        pendingHistory.forEach { (key, delta) ->
            if (key.preference !in removed && key.day > today - HISTORY_DAYS) {
                val total = parse(store.getString(key.preference, null)) + delta
                editor.putString(key.preference, "${total.sent},${total.received},${total.sessions}")
            }
        }
        editor.apply()
        pending.clear()
        pendingHistory.clear()
        lastPrunedDay = today
        lastFlush = SystemClock.elapsedRealtime()
    }

    @Synchronized private fun record(context: Context, id: String, delta: Totals, consentToken: Long) {
        if (consentToken != token(context)) return
        val store = preferences(context)
        val key = if (id in pending || store.contains("row:$id")) id
            else if (store.all.keys.count { it.startsWith("row:") } +
                pending.keys.count { !store.contains("row:$it") } < LIMIT - 1) id
            else UNATTRIBUTED
        pending[key] = (pending[key] ?: Totals()) + delta
        val day = DailyKey(LocalDate.now().toEpochDay(), key)
        pendingHistory[day] = (pendingHistory[day] ?: Totals()) + delta
        if (SystemClock.elapsedRealtime() - lastFlush >= FLUSH_MS) flush(context)
    }

    private fun dayOf(preference: String) =
        preference.substringAfter("day:").substringBefore(':').toLongOrNull() ?: Long.MIN_VALUE

    private fun add(one: Long, two: Long): Long = if (Long.MAX_VALUE - one < two) Long.MAX_VALUE else one + two
}
