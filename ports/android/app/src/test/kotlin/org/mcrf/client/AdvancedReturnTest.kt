package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class AdvancedReturnTest {
    @Test fun eachSettingsSectionIsItsOwnAdvancedReturn(){
        for(from in listOf(ClientSettingsNavigation.VPN,ClientSettingsNavigation.DNS,ClientSettingsNavigation.GEO,ClientSettingsNavigation.UPDATES,ClientSettingsNavigation.ROOT))
            assertEquals(from,ClientSettingsNavigation.back(ClientSettingsNavigation.ADVANCED,"Главная",false,from))
    }
    @Test fun directRoutesLinkReturnsToRoutesNotRoot(){assertEquals("Маршрутизация",ClientSettingsNavigation.back(ClientSettingsNavigation.ADVANCED,"Маршрутизация",true,"Маршрутизация"))}
    @Test fun advancedDoesNotPointToItself(){assertEquals(ClientSettingsNavigation.ROOT,ClientSettingsNavigation.back(ClientSettingsNavigation.ADVANCED,"Главная",false,ClientSettingsNavigation.ADVANCED))}
    @Test fun originOnlyAffectsAdvanced(){assertEquals(ClientSettingsNavigation.ROOT,ClientSettingsNavigation.back(ClientSettingsNavigation.VPN,"Главная",false,ClientSettingsNavigation.DNS))}
    @Test fun legacyCoreOriginUsesNewUpdatesIdentity(){assertEquals(ClientSettingsNavigation.UPDATES,ClientSettingsNavigation.back(ClientSettingsNavigation.ADVANCED,"Главная",false,ClientSettingsNavigation.CORE))}
    @Test fun missingOriginKeepsLegacyBackBehavior(){assertEquals(ClientSettingsNavigation.ROOT,ClientSettingsNavigation.back(ClientSettingsNavigation.ADVANCED,"Главная"))}
}
