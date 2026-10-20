package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class ConnectionPresentationTest {
    @Test fun recoveryAndReturnArePendingNotIdleOrSuccessfullyConnected(){
        for(status in listOf("Восстановление соединения…","Возвращаемся в VPN…")){
            assertTrue(ConnectionPresentation.pending(status));assertFalse(ConnectionPresentation.connected(status));assertFalse(ConnectionPresentation.failed(status))
            assertEquals(status,ConnectionPresentation.progressLabel(status))
        }
    }
    @Test fun targetShowsActualServerAndSectionNotTheCurrentTabOrIdleChoice() {
        assertEquals("Рабочий · WhiteList · 192.0.2.8",ConnectionPresentation.target(true,false,"Рабочий","192.0.2.8",true))
        assertEquals("Другой · VPN · fixture.test",ConnectionPresentation.target(true,false,"Другой","fixture.test",false))
        assertEquals("",ConnectionPresentation.target(false,false,"Старый","fixture.test",true))
    }
    @Test fun pendingAndUnknownOwnerNeverClaimAnOldServerIsConnected() {
        assertEquals("Подключение к серверу…",ConnectionPresentation.target(false,true,"Старый","fixture.test",true))
        assertEquals("Подключено · сервер уточняется",ConnectionPresentation.target(true,false,null,null,null))
    }
    @Test fun pausedAutoCarrierIsNotGreenMerelyBecauseAndroidTunExists() {
        for(status in listOf("VPN подключён · движки БС недоступны, трафик приостановлен","Прокси 127.0.0.1:10808 · движки БС недоступны")) {
            assertFalse(ConnectionPresentation.connected(status))
            assertTrue(ConnectionPresentation.failed(status))
            assertEquals("Движки белых списков недоступны. Трафик приостановлен.",ConnectionPresentation.failureDetail(status))
        }
    }
    @Test fun regularStatesRemainDistinctAndLabelsCannotInjectControlLines() {
        assertTrue(ConnectionPresentation.connected("VPN подключён · прокси 127.0.0.1:10808"))
        assertTrue(ConnectionPresentation.connected("Прокси 127.0.0.1:10808"))
        assertFalse(ConnectionPresentation.connected("Подключение…"))
        assertTrue(ConnectionPresentation.failed("Ошибка подключения: сервер недоступен"))
        assertEquals("сервер недоступен",ConnectionPresentation.failureDetail("Ошибка подключения: сервер недоступен"))
        assertEquals("AB · VPN · xy",ConnectionPresentation.target(true,false,"A\nB","x\ry",false))
    }
}
