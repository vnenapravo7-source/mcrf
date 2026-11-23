package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class UsageQuotaPolicyTest {
    private fun at(value:String)=Instant.parse(value).toEpochMilli()
    @Test fun calendarWindowsAreHalfOpenAndDstAware() {
        val now=at("2026-03-29T12:00:00Z")
        val daily=UsageQuotaPolicy.Rule("day",1,UsageQuotaPolicy.Period.DAILY,zone="Europe/Berlin").window(now)
        assertEquals(23*3_600_000L,daily.end-daily.start)
        val weekly=UsageQuotaPolicy.Rule("week",1,UsageQuotaPolicy.Period.WEEKLY,zone="UTC").window(now)
        assertEquals(at("2026-03-23T00:00:00Z"),weekly.start)
        assertEquals(at("2026-03-30T00:00:00Z"),weekly.end)
        val monthly=UsageQuotaPolicy.Rule("month",1,UsageQuotaPolicy.Period.MONTHLY,zone="UTC").window(at("2028-02-20T00:00:00Z"))
        assertEquals(29*UsageQuotaPolicy.DAY,monthly.end-monthly.start)
    }
    @Test fun customPeriodsAreBounded() {
        val rule=UsageQuotaPolicy.Rule("custom",1,UsageQuotaPolicy.Period.CUSTOM,customStart=10,customEnd=20)
        assertEquals(UsageQuotaPolicy.Window(10,20),rule.window(15))
        try {rule.copy(customEnd=10);fail("empty period")}catch(_:IllegalArgumentException){}
        try {rule.copy(customEnd=91*UsageQuotaPolicy.DAY);fail("unbounded period")}catch(_:IllegalArgumentException){}
    }
    @Test fun nativeSnapshotsDoNotDoubleCountAndNewOwnersStartNewSegments() {
        val meter=UsageQuotaPolicy.Meter()
        assertEquals(30L,meter.sample("a",10,20));assertEquals(0L,meter.sample("a",10,20))
        assertEquals(0L,meter.sample("a",9,19));assertEquals(10L,meter.sample("a",15,25))
        assertEquals(3L,meter.sample("b",1,2))
        assertEquals(Long.MAX_VALUE,UsageQuotaPolicy.add(Long.MAX_VALUE,1))
    }
    @Test fun aggregateAndAttributedViewsNeverAddEachOtherToTotal() {
        val total=UsageQuotaPolicy.Rule("total",1,UsageQuotaPolicy.Period.DAILY)
        val app=total.copy(id="app",scope=UsageQuotaPolicy.Scope.APP,subject="org.example.call")
        val unknown=app.copy(id="unknown",subject=UsageQuotaPolicy.UNKNOWN)
        val native=UsageQuotaPolicy.Delta(1,10)
        val metadata=UsageQuotaPolicy.Delta(1,10,app="org.example.call",domain="example.org",attributedOnly=true)
        assertTrue(native.counts(total));assertTrue(native.counts(unknown));assertFalse(native.counts(app))
        assertFalse(metadata.counts(total));assertTrue(metadata.counts(app));assertFalse(metadata.counts(unknown))
    }
    @Test fun subjectsCannotContainUrlsPayloadOrCredentials() {
        for(subject in listOf("https://user:secret@example.org/path","host/path","host\ncontent")) {
            try {UsageQuotaPolicy.Rule("bad",1,UsageQuotaPolicy.Period.DAILY,scope=UsageQuotaPolicy.Scope.DOMAIN,subject=subject);fail("unsafe metadata")}catch(_:IllegalArgumentException){}
        }
    }
}
