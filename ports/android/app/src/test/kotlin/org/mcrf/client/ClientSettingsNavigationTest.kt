package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class ClientSettingsNavigationTest {
    @Test fun everyChildReturnsToSettings() {
        assertFalse(ClientSettingsNavigation.CAPTCHA in ClientSettingsNavigation.children)
        ClientSettingsNavigation.children.forEach {
            assertTrue(ClientSettingsNavigation.isSettings(it))
            val parent=when(it){ClientSettingsNavigation.MINIMALISM->ClientSettingsNavigation.APPEARANCE
                ClientSettingsNavigation.DIAGNOSTICS->ClientSettingsNavigation.BETA
                ClientSettingsNavigation.DNS->ClientSettingsNavigation.VPN
                ClientSettingsNavigation.GEO->ClientSettingsNavigation.ADVANCED
                ClientSettingsNavigation.EXPERIENCE->ClientSettingsNavigation.ROOT
                else->ClientSettingsNavigation.ROOT}
            assertEquals(parent, ClientSettingsNavigation.back(it, "Логи"))
        }
    }
    @Test fun anubisReturnsToRoutesWithoutReplacingTheSavedSubtab() {
        val page=ClientSettingsNavigation.ANUBIS
        assertFalse(ClientSettingsNavigation.isSettings(page))
        assertFalse(ClientSettingsNavigation.isRoutes(page))
        assertTrue(ClientSettingsNavigation.visible(page,ExperienceMode.SIMPLE))
        assertEquals(ClientSettingsNavigation.ROUTES,ClientSettingsNavigation.mainTab(page))
        assertEquals(ClientSettingsNavigation.ROUTES,ClientSettingsNavigation.back(page,"Логи",true))
        assertEquals("Anubis",ClientSettingsNavigation.tabTitle(page))
    }
    @Test fun settingsReturnsToItsOriginalSection() {
        for (origin in listOf("Главная", "Маршрутизация", ClientSettingsNavigation.WHITELISTS, "Логи", "WARP")) {
            assertEquals(origin, ClientSettingsNavigation.back(ClientSettingsNavigation.ROOT, origin))
            assertFalse(ClientSettingsNavigation.isSettings(origin))
        }
    }
    @Test fun inviteIsAHeaderActionAndWhitelistsKeepTheirTab() {
        assertFalse(ClientSettingsNavigation.INVITE in ClientSettingsNavigation.rootLeading)
        assertEquals(ClientSettingsNavigation.BETA,ClientSettingsNavigation.rootLeading[0])
        assertEquals(ClientSettingsNavigation.APPEARANCE,ClientSettingsNavigation.rootLeading[1])
        assertEquals(ClientSettingsNavigation.DEPLOY,ClientSettingsNavigation.rootLeading[2])
        assertFalse(ClientSettingsNavigation.EXPERIENCE in ClientSettingsNavigation.rootLeading)
        assertFalse(ClientSettingsNavigation.OPENFLUX in ClientSettingsNavigation.rootLeading)
        assertEquals(ClientSettingsNavigation.APPEARANCE,ClientSettingsNavigation.canonical(ClientSettingsNavigation.EXPERIENCE))
        assertEquals(ClientSettingsNavigation.WHITELISTS,ClientSettingsNavigation.canonical(ClientSettingsNavigation.OPENFLUX))
        assertFalse(ClientSettingsNavigation.CONDITIONS in ClientSettingsNavigation.rootLeading)
        assertFalse(ClientSettingsNavigation.visible(ClientSettingsNavigation.BETA,ExperienceMode.SIMPLE))
        for(child in listOf(ClientSettingsNavigation.EXPERIENCE)) {
            assertEquals(ClientSettingsNavigation.ROOT,ClientSettingsNavigation.back(child,"Логи"))
            assertEquals("Логи",ClientSettingsNavigation.back(child,"Логи",true))
        }
        assertEquals("Главная",ClientSettingsNavigation.back(ClientSettingsNavigation.CONDITIONS,"Логи"))
        assertEquals("Главная",ClientSettingsNavigation.back(ClientSettingsNavigation.CONDITIONS,"Логи",true))
        assertFalse(ClientSettingsNavigation.experimental(ClientSettingsNavigation.CONDITIONS))
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
    @Test fun dnsAndGeoKeepLegacyLinksAndReturnToTheirNewParents() {
        assertEquals("Настройки DNS",ClientSettingsNavigation.DNS)
        assertFalse(ClientSettingsNavigation.DNS in ClientSettingsNavigation.rootLeading)
        assertEquals(ClientSettingsNavigation.DNS,ClientSettingsNavigation.canonical("DNS и домены"))
        assertEquals(ClientSettingsNavigation.ADVANCED,ClientSettingsNavigation.canonical(ClientSettingsNavigation.GEO))
        for(direct in listOf(false,true)) {
            assertEquals(ClientSettingsNavigation.VPN,ClientSettingsNavigation.back(ClientSettingsNavigation.DNS,"Логи",direct))
            assertEquals(ClientSettingsNavigation.ADVANCED,ClientSettingsNavigation.back(ClientSettingsNavigation.GEO,"Логи",direct))
        }
        assertEquals(ClientSettingsNavigation.VK,ClientSettingsNavigation.canonical(ClientSettingsNavigation.VK))
        assertEquals(ClientSettingsNavigation.ROOT,ClientSettingsNavigation.back(ClientSettingsNavigation.ADVANCED,"Логи",false,ClientSettingsNavigation.GEO))
        assertEquals("Логи",ClientSettingsNavigation.back(ClientSettingsNavigation.ADVANCED,"Логи",true,ClientSettingsNavigation.GEO))
    }
    @org.junit.Test fun selected_settings_tab_opens_root_but_return_from_other_tab_restores_child() {
        assertEquals(ClientSettingsNavigation.ROOT,ClientSettingsNavigation.settingsTabTarget(ClientSettingsNavigation.VK,ClientSettingsNavigation.APPEARANCE))
        assertEquals(ClientSettingsNavigation.APPEARANCE,ClientSettingsNavigation.settingsTabTarget("Главная",ClientSettingsNavigation.APPEARANCE))
        assertEquals(ClientSettingsNavigation.ROOT,ClientSettingsNavigation.settingsTabTarget("Логи","unknown"))
    }
}
