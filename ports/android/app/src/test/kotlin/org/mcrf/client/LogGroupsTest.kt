package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class LogGroupsTest {
    private val rows=LogEntries.Kind.entries.map {LogEntries.Entry("2026-10-14 12:00:00","source",LogEntries.Level.INFO,it,"Проверка","details")}
    @Test fun everyKindHasOneContentGroup(){rows.forEach {row->assertEquals(1,listOf(LogEntries.Group.CONNECTION,LogEntries.Group.SERVERS,LogEntries.Group.SYSTEM).count {LogEntries.filter(listOf(row),it).size==1})}}
    @Test fun problemsOnlyContainsWarningsAndErrors(){val source=LogEntries.Level.entries.map {rows[0].copy(level=it)};assertEquals(setOf(LogEntries.Level.WARNING,LogEntries.Level.ERROR),LogEntries.filter(source,LogEntries.Group.PROBLEMS).map{it.level}.toSet())}
    @Test fun allDoesNotDiscardEntries(){assertEquals(rows,LogEntries.filter(rows,LogEntries.Group.ALL))}
    @Test fun copiedLogsIncludeSystemInfoAndRedactSecrets(){val value=LogEntries.export(listOf(rows[0].copy(message="password=do-not-leak")),"Android 16 · API 36");assertTrue(value.contains("Android 16"));assertTrue(value.contains("Время\tУровень"));assertFalse(value.contains("do-not-leak"))}
}
