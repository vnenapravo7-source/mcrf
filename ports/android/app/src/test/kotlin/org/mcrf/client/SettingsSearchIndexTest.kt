package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class SettingsSearchIndexTest {
    @Test fun graduatedFunctionsAreDiscoverableAtTheirSettingsDestination() {
        val words=SettingsSearchIndex.keywords(ClientSettingsNavigation.BETA)
        assertFalse(SettingsSearchIndex.matches("поведение mcrf в разных сетях",words))
        assertFalse(SettingsSearchIndex.matches("здоровье",words))
        assertFalse(SettingsSearchIndex.matches("локальная диагностика",words))
        assertTrue(SettingsSearchIndex.matches("автовосстановление",SettingsSearchIndex.keywords(ClientSettingsNavigation.BEHAVIOR)))
        assertTrue(SettingsSearchIndex.matches("kill switch",SettingsSearchIndex.keywords(ClientSettingsNavigation.VPN)))
        assertTrue(SettingsSearchIndex.matches("рандомное оформление каждый раз",SettingsSearchIndex.keywords(ClientSettingsNavigation.APPEARANCE)))
        assertTrue(SettingsSearchIndex.matches("ssid профиль серверы",SettingsSearchIndex.keywords(ClientSettingsNavigation.CONDITIONS)))
        assertTrue(SettingsSearchIndex.matches("расписание маршруты",SettingsSearchIndex.keywords(ClientSettingsNavigation.SCHEDULE)))
        assertEquals(SettingsSearchIndex.keywords(ClientSettingsNavigation.CONDITIONS),SettingsSearchIndex.keywords(ClientSettingsNavigation.SCHEDULE))
        assertFalse(SettingsSearchIndex.matches("расписание",words))
    }
    @Test fun primaryEngineSettingsAreDiscoverableByBothEngineNames() {
        val words=SettingsSearchIndex.keywords(ClientSettingsNavigation.CORE)
        assertTrue(SettingsSearchIndex.matches("sing-box",words))
        assertTrue(SettingsSearchIndex.matches("steer",words))
        assertTrue(SettingsSearchIndex.matches("основное ядро",words))
    }
    @Test fun childSettingsAreFoundWithoutProfileValues() {
        assertTrue(SettingsSearchIndex.matches("карандаш",SettingsSearchIndex.keywords(ClientSettingsNavigation.CONNECTIONS)))
        assertTrue(SettingsSearchIndex.matches("пуш капча",SettingsSearchIndex.keywords(ClientSettingsNavigation.BEHAVIOR)))
        assertTrue(SettingsSearchIndex.matches("темная",SettingsSearchIndex.keywords(ClientSettingsNavigation.APPEARANCE)))
        assertTrue(SettingsSearchIndex.matches("  ","anything"))
        assertFalse(SettingsSearchIndex.matches("несуществующий",SettingsSearchIndex.keywords(ClientSettingsNavigation.VK)))
        assertEquals("Главная",ClientSettingsNavigation.back(ClientSettingsNavigation.CONNECTIONS,"Главная"))
    }
    @Test fun relocatedSettingsRemainSearchable() {
        assertTrue(SettingsSearchIndex.matches("ручная капча",SettingsSearchIndex.keywords(ClientSettingsNavigation.CAPTCHA)))
        assertTrue(SettingsSearchIndex.matches("автодеплой ssh",SettingsSearchIndex.keywords(ClientSettingsNavigation.DEPLOY)))
        assertTrue(SettingsSearchIndex.matches("openflux",SettingsSearchIndex.keywords(ClientSettingsNavigation.WHITELISTS)))
        assertTrue(SettingsSearchIndex.matches("openflux параметры",SettingsSearchIndex.keywords(ClientSettingsNavigation.OPENFLUX)))
    }
    @Test fun consolidatedSettingsAreDiscoverableAtTheirActiveDestination() {
        assertFalse(ClientSettingsNavigation.CONNECTIONS in ClientSettingsNavigation.children)
        assertTrue(SettingsSearchIndex.matches("авторизация пароль",SettingsSearchIndex.keywords(ClientSettingsNavigation.VPN)))
        assertTrue(SettingsSearchIndex.matches("hotspot адрес телефона",SettingsSearchIndex.keywords(ClientSettingsNavigation.VPN)))
        assertTrue(SettingsSearchIndex.matches("движки csqtt",SettingsSearchIndex.keywords(ClientSettingsNavigation.UPDATES)))
        assertTrue(SettingsSearchIndex.matches("tls xray сброс",SettingsSearchIndex.keywords(ClientSettingsNavigation.ADVANCED)))
        assertTrue(SettingsSearchIndex.matches("bootstrap подключение серверов",SettingsSearchIndex.keywords(ClientSettingsNavigation.DNS)))
        assertTrue(SettingsSearchIndex.matches("настройки dns домены",SettingsSearchIndex.keywords(ClientSettingsNavigation.VPN)))
        assertTrue(SettingsSearchIndex.matches("geoip geosite базы загрузка",SettingsSearchIndex.keywords(ClientSettingsNavigation.ADVANCED)))
    }
}
