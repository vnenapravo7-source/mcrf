package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class QuickTilePresentationReviewTest {
    @Test fun pausedCarrierNeverLightsUpEitherConnectionTile() {
        for(status in listOf("VPN подключён · движки БС недоступны, трафик приостановлен", "Прокси 127.0.0.1:10808 · движки БС недоступны"))
            for(command in listOf("vpn","bs"))for(bs in listOf(false,true))assertFalse(QuickTilePresentation.active(command,status,bs))
    }
    @Test fun workingConnectionLightsOnlyItsOwnSection() {
        for(status in listOf("VPN подключён · прокси 127.0.0.1:10808", "Прокси 127.0.0.1:10808")) {
            assertTrue(QuickTilePresentation.active("vpn",status,false))
            assertFalse(QuickTilePresentation.active("bs",status,false))
            assertTrue(QuickTilePresentation.active("bs",status,true))
            assertFalse(QuickTilePresentation.active("vpn",status,true))
        }
    }
    @Test fun pendingFailedAndStoppedAreInactive() {
        for(status in listOf("Подключение…", "Восстановление соединения…", "Ошибка подключения: fixture", "Отключено", "БС на паузе · Wi-Fi"))
            for(command in listOf("vpn","bs"))assertFalse(QuickTilePresentation.active(command,status,command=="bs"))
    }
    @Test fun routeAndDetectorTilesDoNotInheritTunnelState() {
        for(command in listOf("rules","auto-bs","unknown"))assertFalse(QuickTilePresentation.active(command,"VPN подключён",false))
    }
}
