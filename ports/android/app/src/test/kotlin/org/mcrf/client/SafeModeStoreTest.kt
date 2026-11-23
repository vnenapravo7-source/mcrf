package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class SafeModeStoreTest {
    @Test fun betaIsOffByDefaultAndRestoreOnlyChangesTheGate() {
        val settings=mutableMapOf<String,Any>("experimentalOptions" to "saved","selected" to "server")
        val store=SafeModeStore({settings[SafeModeStore.KEY] as? Boolean?:false},{settings[SafeModeStore.KEY]=it})
        assertFalse(store.enabled())
        assertTrue(store.allowsExperimental())
        store.save(true)
        assertFalse(store.allowsExperimental())
        store.restore()
        assertTrue(store.allowsExperimental())
        assertEquals("saved",settings["experimentalOptions"])
        assertEquals("server",settings["selected"])
        assertTrue(SafeModeStore.TITLE.contains("Beta"))
        assertEquals("Поведение MCRF в разных сетях",ConditionProfiles.TITLE)
    }
    @Test fun profilesResumeWithSameIdentityAndSettingsAfterSafeMode() {
        val profiles=listOf(ConditionProfile("wifi","Дом",ProfileCondition.WIFI,mode=ExperienceMode.EXPERT))
        val before=ConditionProfiles.json(profiles).toString()
        val network=ConditionEnvironment(wifi=true)
        assertEquals("wifi",ConditionProfiles.resolve(profiles,network,false)?.id)
        assertNull(ConditionProfiles.resolve(profiles,network,true))
        assertEquals(before,ConditionProfiles.json(profiles).toString())
        assertEquals(profiles.single(),ConditionProfiles.resolve(profiles,network,false))
    }
}
