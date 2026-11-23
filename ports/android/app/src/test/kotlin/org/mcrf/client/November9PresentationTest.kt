package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class November9PresentationTest {
    @Test fun timingRecordsBecomeReadableWithoutLosingValuesOrOriginalIdentifiers() {
        val original="worker_start=343ms mono=339222015 pid=7286"
        val row=LogEntries.parse("2026-11-09 12:00:00 [Тайминги] $original")
        assertEquals("Этап: Запуск фонового обработчика.",row.message);assertFalse(row.message.contains("7286"))
        assertTrue(row.details.endsWith(original))
    }
    @Test fun readinessAndTurnAuthorizationAreExplained() {
        assertEquals("Служба готова, подключение ещё не запущено · попытка 0",LogMessagePresentation.explain("IDLE · READY · попытка 0"))
        val row=LogEntries.parse("2026-11-09 12:00:00 [Ошибка движка] child error: [TURN][STUN] Allocate: STUN error 401")
        assertTrue(row.message.contains("TURN-сервер не принял данные подключения"))
        assertTrue(row.details.contains("Allocate: STUN error 401"))
    }
    @Test fun remainingPhoneTimingPhasesKeepDurationAndReadableLabels() {
        for(key in listOf("ui_queue","masque_prepare","resolve","detour")) {
            val shown=LogMessagePresentation.explain("$key=27ms")
            assertFalse(shown.contains("$key="));assertTrue(shown.endsWith("27 мс"))
        }
    }
    @Test fun compactConnectionCaptionHasNoRedundantVpnModeAndRejectsControlCharacters() {
        assertEquals("VPN подключён: Сервер, 192.0.2.1",ConnectionPresentation.compactTarget("VPN подключён","Сервер","192.0.2.1"))
        assertEquals("VPN подключён",ConnectionPresentation.compactTarget("VPN подключён",null,null))
        assertFalse(ConnectionPresentation.compactTarget("VPN","a\nb","192.0.2.1\u0000").any(Char::isISOControl))
    }
    @Test fun everyWallpaperCombinesDifferentMotifsAndHiddenCastingIsNotSelected() {
        assertTrue(WallpaperCombinations.all.all {it.first!=it.second})
        assertFalse(ConnectionAppearance.choices.containsKey(ConnectionAppearance.CASTING))
        assertEquals(ConnectionAppearance.DEFAULT,ConnectionAppearance.style(ConnectionAppearance.CASTING))
    }
}
