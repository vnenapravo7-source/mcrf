package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class ExperienceModeStoreTest {
    @Test fun freshInstallIsSimpleAndMigrationIsWrittenOnce() {
        var saved:String?=null
        var writes=0
        val store=ExperienceModeStore({saved},{saved=it;writes++},{false})
        assertEquals(ExperienceMode.SIMPLE,store.load())
        assertEquals("simple",saved)
        assertEquals(ExperienceMode.SIMPLE,ExperienceModeStore({saved},{fail("must not migrate twice")},{true}).load())
        assertEquals(1,writes)
    }
    @Test fun existingConfigurationMigratesToExpertAndSwitchesPreserveEverythingElse() {
        val prefs=mutableMapOf("selected" to "server-id","dns" to "private-resolver","generation" to "2.1.5")
        val original=prefs.toMap()
        val store=ExperienceModeStore({prefs[ExperienceModeStore.KEY]},{prefs[ExperienceModeStore.KEY]=it},{true})
        assertEquals(ExperienceMode.EXPERT,store.load())
        store.save(ExperienceMode.SIMPLE)
        assertEquals(ExperienceMode.SIMPLE,store.load())
        store.save(ExperienceMode.EXPERT)
        assertEquals(original,prefs.filterKeys {it!=ExperienceModeStore.KEY})
    }
    @Test fun televisionKeepsSimpleWithoutReadingOrRewritingSavedMode() {
        val store=ExperienceModeStore(
            {error("Television does not read the saved presentation mode")},
            {fail("Television must preserve the saved mode")},
            {error("Television does not migrate the saved mode")},
            fixedMode=ExperienceMode.SIMPLE
        )
        assertEquals(ExperienceMode.SIMPLE,store.load())
        store.save(ExperienceMode.EXPERT)
        assertEquals(ExperienceMode.SIMPLE,store.load())
    }
    @Test fun unknownRestoredModeKeepsControlsVisible() {
        assertEquals(ExperienceMode.EXPERT,ExperienceModeStore.parse("future-mode"))
    }
    @Test fun simpleHidesInternalsIncludingLegacyAliasesButKeepsEssentials() {
        val nav=ClientSettingsNavigation
        for(page in listOf(nav.DNS,nav.CORE,nav.GEO,nav.ADVANCED,nav.XRAY,nav.TLS,nav.VPN,nav.SHARING,"WARP","Логи")) {
            assertFalse(page,nav.visible(page,ExperienceMode.SIMPLE))
            assertFalse(page,nav.visible(nav.canonical(page),ExperienceMode.SIMPLE)&&page!=nav.CORE)
            assertTrue(page,nav.visible(page,ExperienceMode.EXPERT))
        }
        for(page in listOf(nav.INVITE,nav.APPEARANCE,nav.BEHAVIOR,nav.UPDATES,nav.EXPERIENCE,nav.DEPLOY,nav.VK,nav.OPENFLUX,nav.CONDITIONS,nav.SCHEDULE,"Главная",nav.WHITELISTS,"Маршрутизация"))
            assertTrue(page,nav.visible(page,ExperienceMode.SIMPLE))
        assertEquals("Скрытые настройки остаются сохранены.",ExperienceModeStore.SAVED_COPY)
    }
}
