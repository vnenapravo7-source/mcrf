package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class LogEntriesTest {
    @Test fun anubisFilterIncludesShizukuAndLegacyFreezeEvents(){
        val rows=LogEntries.read(
            "2026-10-14 12:00:00 [Anubis] Открыт Telegram с VPN.\n"+
                "2026-10-14 12:01:00 [Shizuku] Shizuku подключён.\n"+
                "2026-10-14 12:02:00 [Заморозка приложений] Восстановление завершено.\n"+
                "2026-10-14 12:03:00 [Клиент] Системный VPN подключён."
        )
        val filtered=LogEntries.filter(rows,LogEntries.Group.ANUBIS)
        assertEquals(listOf("Заморозка приложений","Shizuku","Anubis"),filtered.map {it.source})
        assertTrue(filtered.all {it.kind==LogEntries.Kind.ANUBIS})
    }

    @Test fun missingPhysicalIpv6FallbackIsALimitationRatherThanConnectionFailure() {
        val entry=LogEntries.parse("2026-11-07 12:00:00 [DNS] У физической сети нет IPv6-маршрута в интернет. Быстрый отказ недоступного IPv6.")
        assertEquals(LogEntries.Level.WARNING,entry.level)
        assertEquals(LogEntries.Level.ERROR,LogEntries.parse("2026-11-07 12:00:00 [DNS] DNS недоступен: ошибка подключения").level)
    }

    @Test fun stopDiagnosticsKeepUnknownExitInformationalAndForcedExitWarning() {
        val clean=LogEntries.parse("2026-10-17 14:00:00 [Движки] CSQTT · "+CarrierStopDiagnostics.completed(100,false,null))
        assertEquals(LogEntries.Level.INFO,clean.level);assertEquals(LogEntries.Kind.ENGINE,clean.kind)
        val forced=LogEntries.parse("2026-10-17 14:00:00 [Движки] CSQTT · "+CarrierStopDiagnostics.completed(22000,true,143))
        assertEquals(LogEntries.Level.WARNING,forced.level);assertTrue(forced.details.contains("код=143"))
    }
    @Test fun exportIncludesActualLocalVersionInventoryWithoutClaimingAnUpdateCheck() {
        val info=EngineLogInventory.format(listOf(EngineLogInventory.Item("csqtt",5),EngineLogInventory.Item("csqtt25",1)))
        val report=LogEntries.export(emptyList(),"MCRF fixture\n$info")
        assertTrue(report.contains("CSQTT: versionCode=5"));assertTrue(report.contains("CSQTT 2.5: versionCode=1"));assertTrue(report.contains("не проверка актуальности"))
    }
    @Test fun structuralRoutingSummaryKeepsItsSourceAndStartupTimeoutIsVisible() {
        val routing=LogEntries.parse("2026-10-16 20:00:00 [Маршруты] Маршруты: напрямую=1. DNS: серверов=2")
        assertEquals(LogEntries.Kind.ROUTING,routing.kind)
        val startup=LogEntries.parse("2026-10-16 20:00:01 [Движки] CSQTT · +60000 мс · Истёк запуск; ready=false")
        assertEquals(LogEntries.Kind.ENGINE,startup.kind)
        assertEquals(LogEntries.Level.WARNING,startup.level)
        assertEquals(listOf(startup),LogEntries.filter(listOf(routing,startup),LogEntries.Group.PROBLEMS))
        assertTrue(startup.details.contains("ready=false"))
    }
    @Test fun separatesColumnsAndKeepsNewestFirst(){val rows=LogEntries.read("2026-10-14 12:00:00 [Клиент] Системный VPN подключён\n2026-10-14 12:01:00 [Замеры] Проверено серверов: 21, отвечают: 18");assertEquals(2,rows.size);assertEquals("2026-10-14 12:01:00",rows.first().timestamp);assertEquals(LogEntries.Kind.SERVERS,rows.first().kind);assertEquals(LogEntries.Level.SUCCESS,rows.last().level)}
    @Test fun explainsTimeoutWithoutLosingDiagnostic(){val row=LogEntries.parse("2026-10-14 12:00:00 [Ядро] ERROR outbound: i/o timeout");assertEquals(LogEntries.Level.ERROR,row.level);assertTrue(row.message.startsWith("Сервер не ответил"));assertTrue(row.details.contains("i/o timeout"))}
    @Test fun explainsDnsAndCategorizesIt(){val row=LogEntries.parse("2026-10-14 12:00:00 [Ядро] lookup example.com: no such host");assertEquals(LogEntries.Kind.DNS,row.kind);assertTrue(row.message.startsWith("Не удалось получить адрес"))}
    @Test fun doesNotReplaceReadableRussian(){val row=LogEntries.parse("2026-10-14 12:00:00 [Подписки] Обновлено по расписанию: 2");assertEquals("Обновлено по расписанию: 2",row.message);assertEquals(LogEntries.Kind.SUBSCRIPTIONS,row.kind)}
    @Test fun filtersByLevelAndKind(){val rows=LogEntries.read("2026-10-14 12:00:00 [Ошибка] TG WS завершился (1)\n2026-10-14 12:01:00 [Подписки] Обновлено по расписанию: 2");assertEquals(1,LogEntries.filter(rows,LogEntries.Level.ERROR,LogEntries.Kind.TELEGRAM).size);assertTrue(LogEntries.filter(rows,LogEntries.Level.ERROR,LogEntries.Kind.SUBSCRIPTIONS).isEmpty());assertEquals(rows,LogEntries.filter(rows,null,null))}
    @Test fun detailsCannotExposeCredentials(){val row=LogEntries.parse("2026-10-14 12:00:00 [Ядро] ERROR authentication failed password=fixture-secret");assertFalse(row.details.contains("fixture-secret"));assertTrue(row.message.startsWith("Сервер не принял"))}
    @Test fun oldMalformedRowsAreStillAvailable(){val row=LogEntries.parse("old unstructured diagnostic");assertEquals("—",row.timestamp);assertEquals("old unstructured diagnostic",row.details);assertEquals("Событие службы приложения",row.message)}
    @Test fun unknownCoreDetailsRemainAccessible(){val row=LogEntries.parse("2026-10-14 12:00:00 [Ядро] INFO future native message");assertTrue(row.message.startsWith("Служебное событие"));assertTrue(row.details.contains("future native message"))}
    @Test fun longMessageIsConciseButCompleteDetailsAreRetained() {
        val message = "Проверка подключения: " + "длинное сообщение ".repeat(90) + "окончание"
        val row = LogEntries.parse("2026-10-14 12:00:00 [Клиент] $message")
        assertTrue(row.message.length<=180);assertTrue(row.message.startsWith("Проверка подключения:"))
        assertTrue(row.details.endsWith("окончание"))
    }
    @Test fun exportedFriendlyReportRetainsRedactedDiagnostics() {
        val row = LogEntries.parse("2026-10-14 12:00:00 [Ядро] ERROR authentication failed password=fixture-secret")
        val report = LogEntries.export(listOf(row), "Android fixture-model")
        assertTrue(report.contains("Android fixture-model"))
        assertTrue(report.contains(row.message))
        assertTrue(report.contains("authentication failed"))
        assertFalse(report.contains("fixture-secret"))
        assertFalse(row.message.contains("Android fixture-model"))
    }
    @Test fun encryptedMcrfPayloadCannotReachRowsOrDetails() {
        val row = LogEntries.parse("2026-10-14 12:00:00 [Импорт] mcrf://encrypted/v1/fixture-ciphertext_ABC-123")
        assertFalse(row.message.contains("fixture-ciphertext"))
        assertFalse(row.details.contains("fixture-ciphertext"))
        assertTrue(row.details.contains("[шифрованный профиль скрыт]"))
    }
    @Test fun happCryptVariantsAreHiddenCaseInsensitively() {
        for (link in listOf("happ://crypt/fixture-happ", "HAPP://CRYPT5/fixture-happ", "happ://crypt2/fixture-happ?extra=abc")) {
            val row = LogEntries.parse("2026-10-14 12:00:00 [Импорт] \"$link\" продолжение")
            assertFalse(row.message.contains("fixture-happ"))
            assertFalse(row.details.contains("fixture-happ"))
            assertTrue(row.message.endsWith("продолжение"))
        }
    }
    @Test fun exportAlsoRedactsUnparsedEncryptedDetailsAndSystemText() {
        val row = LogEntries.Entry("—", "Импорт", LogEntries.Level.INFO, LogEntries.Kind.CONNECTION,
            "mcrf://encrypted/v1/fixture-message", "happ://crypt/fixture-details")
        val report = LogEntries.export(listOf(row), "MCRF://ENCRYPTED/v1/fixture-system")
        for (payload in listOf("fixture-message", "fixture-details", "fixture-system")) assertFalse(report.contains(payload))
    }
    @Test fun technicalIdentifiersRemainInDetailsOnly() {
        for(raw in listOf("2026-11-09 12:00:00 [Тайминг] worker_start=343ms mono=339222015 pid=7786",
            "2026-11-09 12:00:00 [Ошибка движка] CSQTT: код завершения=143 ready=false mono=1234")) {
            val row=LogEntries.parse(raw)
            assertFalse(row.message.contains("mono"));assertFalse(row.message.contains("pid"));assertFalse(row.message.contains("ready="))
            assertEquals(raw,row.details)
        }
    }
    @Test fun criticalKnownErrorsKeepTheirMeaningInShortRows() {
        val row=LogEntries.parse("2026-11-09 12:00:00 [Ошибка движка] TURN child error: STUN error 401 mono=1234")
        assertEquals(LogEntries.Level.ERROR,row.level)
        assertTrue(row.message.contains("не принял данные"));assertTrue(row.details.contains("401"))
    }

    @Test fun ordinaryLongMessagesShowOnlyTheFirstSentence() {
        val raw="2026-11-11 12:00:00 [Подключение] Движок подготовил соединение. Подробное описание дополнительных этапов и проверки доступности сервера."
        val row=LogEntries.parse(raw)
        assertEquals("Движок подготовил соединение.",row.message)
        assertEquals(raw,row.details)
    }
    @Test fun singleSentenceRowsHaveABoundedPreview() {
        val raw="2026-11-11 12:00:00 [Подключение] "+ "Подготовка соединения ".repeat(12)
        val row=LogEntries.parse(raw)
        assertTrue(row.message.length<=100)
        assertTrue(row.message.endsWith("…"))
        assertEquals(raw,row.details)
    }
    @Test fun groupsAdjacentTimingRowsWithoutCrossingAnotherEvent() {
        val start=LogEntries.parse("2026-11-11 12:00:00 [Тайминги] ui_queue=1ms")
        val prepare=LogEntries.parse("2026-11-11 12:00:00 [Тайминги] ui_prepare=5ms")
        val boundary=LogEntries.parse("2026-11-11 12:00:00 [Клиент] Профиль прочитан")
        val rows=LogEntries.groupAdjacent(listOf(prepare,start,boundary,start))
        assertEquals(listOf(2,1,1),rows.map {it.count})
        assertEquals(start.details+"\n"+prepare.details,rows.first().details)
        assertEquals(boundary,rows[1])
    }
    @Test fun timelineKeepsTimingOnlyInTheOutcomeDetails() {
        val ready=LogEntries.parse("2026-11-11 12:00:02 [Клиент] Системный VPN подключён")
        val timing=LogEntries.parse("2026-11-11 12:00:01 [Тайминги] ui_prepare=5ms")
        val shown=LogEntries.timeline(listOf(ready,timing)).single()
        assertEquals("VPN подключён",shown.message)
        assertEquals(2,shown.count)
        assertTrue(shown.details.contains(timing.details))
        assertTrue(LogEntries.timeline(listOf(timing)).isEmpty())
    }
}
