package org.mcrf.client

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** Byte metadata only. Unknown attribution is a real bucket, never a guessed package or host. */
object UsageQuotaPolicy {
    const val UNKNOWN = "unknown"
    const val DAY = 86_400_000L
    const val RETENTION = 93 * DAY
    enum class Period { DAILY, WEEKLY, MONTHLY, CUSTOM }
    enum class Action { WARN, SWITCH_SERVER, SWITCH_SUBSCRIPTION }
    enum class Scope { TOTAL, APP, DOMAIN, SERVER, SUBSCRIPTION }
    data class Window(val start: Long, val end: Long)
    data class Rule(val id: String, val bytes: Long, val period: Period, val action: Action = Action.WARN,
                    val scope: Scope = Scope.TOTAL, val subject: String = UNKNOWN,
                    val customStart: Long = 0, val customEnd: Long = 0,
                    val zone: String = ZoneId.systemDefault().id) {
        init {
            require(id.matches(Regex("[A-Za-z0-9-]{1,64}")) && bytes > 0)
            require(subject.length in 1..253 && subject.none { it.isISOControl() })
            require(validSubject(scope,subject))
            ZoneId.of(zone)
            if (period == Period.CUSTOM) require(customStart >= 0 && customEnd > customStart && customEnd - customStart <= 90 * DAY)
        }
        fun window(now: Long): Window {
            if (period == Period.CUSTOM) return Window(customStart, customEnd)
            val date = Instant.ofEpochMilli(now).atZone(ZoneId.of(zone)).toLocalDate()
            val start = when (period) {
                Period.DAILY -> date
                Period.WEEKLY -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                Period.MONTHLY -> date.withDayOfMonth(1)
                else -> error("custom")
            }
            val end = when (period) { Period.DAILY -> start.plusDays(1); Period.WEEKLY -> start.plusWeeks(1); else -> start.plusMonths(1) }
            return Window(start.atStartOfDay(ZoneId.of(zone)).toInstant().toEpochMilli(), end.atStartOfDay(ZoneId.of(zone)).toInstant().toEpochMilli())
        }
    }
    data class Delta(val at: Long, val bytes: Long, val app: String = UNKNOWN, val domain: String = UNKNOWN,
                     val server: String = UNKNOWN, val subscription: String = UNKNOWN,
                     val attributedOnly: Boolean = false) {
        init {
            require(at >= 0 && bytes >= 0)
            require(validSubject(Scope.APP,app)&&validSubject(Scope.DOMAIN,domain)&&validSubject(Scope.SERVER,server)&&validSubject(Scope.SUBSCRIPTION,subscription))
        }
        fun subject(scope: Scope) = when (scope) { Scope.TOTAL -> UNKNOWN; Scope.APP -> app; Scope.DOMAIN -> domain; Scope.SERVER -> server; Scope.SUBSCRIPTION -> subscription }
        fun counts(rule: Rule): Boolean =
            (if (rule.scope in setOf(Scope.APP, Scope.DOMAIN)) {
                if (rule.subject == UNKNOWN) !attributedOnly else attributedOnly
            } else !attributedOnly) &&
                (rule.scope == Scope.TOTAL || subject(rule.scope) == rule.subject)
    }
    fun add(a: Long, b: Long): Long { require(a >= 0 && b >= 0); return if (Long.MAX_VALUE - a < b) Long.MAX_VALUE else a + b }
    private fun validSubject(scope: Scope, subject: String): Boolean {
        if(subject==UNKNOWN)return true
        return when(scope) {
            Scope.TOTAL->false
            Scope.APP->subject.length<=253&&subject.matches(Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+"))
            Scope.DOMAIN->subject.length<=253&&subject==subject.lowercase(java.util.Locale.ROOT)&&subject.split('.').all {it.matches(Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"))}
            Scope.SERVER->subject.matches(Regex("[a-f0-9]{64}"))
            Scope.SUBSCRIPTION->subject.matches(Regex("[A-Za-z0-9-]{1,64}"))
        }
    }
    /** A new owner starts a native segment. Late/duplicate snapshots never add bytes twice. */
    class Meter {
        private var owner = ""
        private var tx = 0L
        private var rx = 0L
        private var accountingEpoch:String? = null
        private var accountingEnabled:Boolean? = null
        /** A toggle can happen twice between samples. Its persisted epoch still
         * invalidates the baseline, so disabled traffic cannot trip a quota. */
        @Synchronized fun sampleAccounting(session:String,sent:Long,received:Long,enabled:Boolean,epoch:String):Long {
            val changed=accountingEpoch!=null && accountingEpoch!=epoch
            val previouslyEnabled=accountingEnabled
            val bytes=sample(session,sent,received)
            accountingEpoch=epoch;accountingEnabled=enabled
            return if(!enabled || changed || previouslyEnabled==false)0 else bytes
        }
        @Synchronized fun sample(session: String, sent: Long, received: Long): Long {
            require(sent >= 0 && received >= 0)
            if (session != owner) { owner = session; tx = 0; rx = 0 }
            if (sent < tx || received < rx) return 0
            val delta = add(sent - tx, received - rx)
            tx = sent; rx = received
            return delta
        }
        @Synchronized fun reset() { owner = ""; tx = 0; rx = 0;accountingEpoch=null;accountingEnabled=null }
    }
}
