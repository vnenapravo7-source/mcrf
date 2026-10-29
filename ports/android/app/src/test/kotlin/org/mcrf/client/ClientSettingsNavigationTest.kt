package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class ClientSettingsNavigationTest {
    @Test fun everyChildReturnsToSettings() {
        assertFalse(ClientSettingsNavigation.CAPTCHA in ClientSettingsNavigation.children)
        ClientSettingsNavigation.children.forEach {
            assertTrue(ClientSettingsNavigation.isSettings(it))
            val parent=if(it==ClientSettingsNavigation.MINIMALISM)ClientSettingsNavigation.APPEARANCE else ClientSettingsNavigation.ROOT
            assertEquals(parent, ClientSettingsNavigation.back(it, "Логи"))
        }
    }
    @Test fun settingsReturnsToItsOriginalSection() {
        for (origin in listOf("Главная", "Маршрутизация", ClientSettingsNavigation.WHITELISTS, "Логи", "WARP")) {
            assertEquals(origin, ClientSettingsNavigation.back(ClientSettingsNavigation.ROOT, origin))
            assertFalse(ClientSettingsNavigation.isSettings(origin))
        }
    }
    @Test fun deploymentIsThirdSettingAndWhitelistsReplaceItsTab() {
        assertEquals(ClientSettingsNavigation.INVITE,ClientSettingsNavigation.rootLeading[0])
        assertEquals(ClientSettingsNavigation.DEPLOY,ClientSettingsNavigation.rootLeading[3])
        assertTrue(ClientSettingsNavigation.isSettings(ClientSettingsNavigation.DEPLOY))
        assertEquals(ClientSettingsNavigation.ROOT,ClientSettingsNavigation.back(ClientSettingsNavigation.DEPLOY,"Главная"))
        assertEquals(listOf("Главная",ClientSettingsNavigation.WHITELISTS,"Маршрутизация","Логи"),ClientSettingsNavigation.mainTabs)
        assertEquals("VPN",ClientSettingsNavigation.tabTitle(ClientSettingsNavigation.mainTabs.first()))
        assertFalse(ClientSettingsNavigation.isSettings(ClientSettingsNavigation.WHITELISTS))
    }
    @Test fun malformedRetainedOriginCannotLoopInsideSettings() {
        (ClientSettingsNavigation.children + ClientSettingsNavigation.ROOT).forEach {
            assertEquals("Главная", ClientSettingsNavigation.back(ClientSettingsNavigation.ROOT, it))
        }
    }
    @Test fun directWhitelistChildrenReturnToWhitelistsButSettingsEntryKeepsItsParent() {
        for(page in listOf(ClientSettingsNavigation.VK,ClientSettingsNavigation.OPENFLUX)){
            assertEquals(ClientSettingsNavigation.WHITELISTS,ClientSettingsNavigation.back(page,ClientSettingsNavigation.WHITELISTS,true))
            assertEquals(ClientSettingsNavigation.ROOT,ClientSettingsNavigation.back(page,ClientSettingsNavigation.WHITELISTS,false))
        }
        assertEquals(ClientSettingsNavigation.APPEARANCE,ClientSettingsNavigation.back(ClientSettingsNavigation.MINIMALISM,ClientSettingsNavigation.WHITELISTS,true))
        assertEquals("Главная",ClientSettingsNavigation.back(ClientSettingsNavigation.CAPTCHA,ClientSettingsNavigation.ROOT,true))
    }
    @Test fun item41RemovesDuplicateSectionsAndMovesCoreToUpdates(){
        for(page in listOf(ClientSettingsNavigation.CONNECTIONS,ClientSettingsNavigation.CAPTCHA,ClientSettingsNavigation.SHARING,ClientSettingsNavigation.TLS,ClientSettingsNavigation.XRAY))
            assertFalse(page in ClientSettingsNavigation.children)
        assertFalse(ClientSettingsNavigation.CORE in ClientSettingsNavigation.children)
        assertEquals(ClientSettingsNavigation.UPDATES,ClientSettingsNavigation.canonical(ClientSettingsNavigation.CORE))
        assertEquals(ClientSettingsNavigation.VPN,ClientSettingsNavigation.canonical("VPN и локальный прокси"))
        assertEquals(ClientSettingsNavigation.UPDATES,ClientSettingsNavigation.canonical("Обновления"))
        assertEquals(ClientSettingsNavigation.ADVANCED,ClientSettingsNavigation.canonical(ClientSettingsNavigation.TLS))
        assertEquals(ClientSettingsNavigation.VPN,ClientSettingsNavigation.canonical(ClientSettingsNavigation.SHARING))
    }
}
