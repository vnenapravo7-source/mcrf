package org.mcrf.client

import java.text.SimpleDateFormat
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class LogRetentionTest {
    private val format=SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.ROOT)
    private val now=format.parse("2026-10-22 12:00:00")!!.time
    @Test fun exactChoicesDefaultAndLabels() {
        assertEquals(listOf(5,15,30,60,180,1440),LogRetention.minutes)
        assertEquals(listOf("5м","15м","30м","60м","3ч","1д"),LogRetention.minutes.map(LogRetention::label))
        assertEquals(60,LogRetention.normalize(-1));assertEquals(60,LogRetention.DEFAULT)
    }
    @Test fun retainsOnlySelectedWindowIncludingBoundaryButNotFutureOrMalformed() {
        val rows=listOf("2026-10-22 10:59:59 [Клиент] old","2026-10-22 11:00:00 [Клиент] boundary","2026-10-22 11:59:00 [Клиент] recent","2026-10-22 12:01:00 [Клиент] future","unparseable")
        val kept=LogRetention.retain(rows.joinToString("\n"),60,now)
        assertTrue(kept.contains("boundary"));assertTrue(kept.contains("recent"))
        for(value in listOf("old","future","unparseable"))assertFalse(kept.contains(value))
        assertFalse(LogRetention.retain(rows.joinToString("\n"),5,now).contains("boundary"))
    }
    @Test fun preservesActualFailureInFriendlyViewAndRedactsSecrets() {
        val row="2026-10-22 11:59:59 [Ошибка движка] XRAY · проверка профиля · код завершения=1 · error: DNS failed password=fixture-secret"
        val kept=LogRetention.retain(row,60,now)
        assertFalse(kept.contains("fixture-secret"))
        assertTrue(LogEntries.parse(kept.trim()).message.contains("код завершения=1"))
    }
    @Test fun boundsBytesWithMultibyteTextAndKeepsLatestRows() {
        val rows=(0..1500).joinToString("\n"){"2026-10-22 11:59:59 [Клиент] row-$it "+"я".repeat(1000)}
        val kept=LogRetention.retain(rows,60,now)
        assertTrue(kept.toByteArray(Charsets.UTF_8).size<=LogRetention.MAX_BYTES)
        assertTrue(kept.contains("row-1500 "));assertFalse(kept.contains("row-0 "))
    }
}
