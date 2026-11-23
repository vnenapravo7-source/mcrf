package org.mcrf.client

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class AppearanceSettingsTest {
    @Test fun amoledPinsTheEdgePanelByDefaultAndPreservesAnExplicitChoice() {
        fun preferences(theme:String,pinned:Boolean?=null)=Proxy.newProxyInstance(
            javaClass.classLoader,arrayOf(SharedPreferences::class.java)
        ) {_,method,args->
            when(method.name){
                "getString"->if(args!![0]==HomeUiTheme.MODE_KEY)theme else args[1]
                "getBoolean"->if(args!![0]==EdgePanelAppearance.PINNED&&pinned!=null)pinned else args[1]
                else->error("Unexpected preference operation: ${method.name}")
            }
        } as SharedPreferences
        assertTrue(EdgePanelAppearance.pinned(preferences("amoled")))
        assertTrue(EdgePanelAppearance.pinned(preferences("amoled-background")))
        assertFalse(EdgePanelAppearance.pinned(preferences("dark")))
        assertFalse(EdgePanelAppearance.pinned(preferences("light")))
        assertFalse(EdgePanelAppearance.pinned(preferences("amoled",false)))
        assertTrue(EdgePanelAppearance.pinned(preferences("light",true)))
    }

    @Test fun mainOnlyToggleUsesTheClassicSidebarStyle() {
        for(style in ConnectionAppearance.toggleChoices.keys) {
            assertEquals(style,ConnectionAppearance.sidebarToggleStyle(style,false))
            assertEquals(ConnectionAppearance.TOGGLE,ConnectionAppearance.sidebarToggleStyle(style,true))
        }
        assertEquals(ConnectionAppearance.TOGGLE,ConnectionAppearance.sidebarToggleStyle(ConnectionAppearance.BLUEPRINT,false))
    }

    @Test fun upgradeRemovesOnlyRetiredSettingsAndIsIdempotent() {
        val values=linkedMapOf<String,Any>(
            "bearCompanionEnabled" to true,"bearCompanionLeft" to false,
            "bearCompanionY" to 120,"bearCompanionCollapsed" to true,"appearanceSettingsPill" to false,
            RandomAppearance.KEY to true,ExperienceModeStore.KEY to "simple",
            ConnectionAppearance.MERGED to true,"selectedServer" to "fixture-server",
            UserThemeColors.keys.first() to "#123456","unrelatedPreference" to 7)
        val preserved=values.filterKeys {it !in AppearanceSettings.retiredKeys}
        var writes=0
        val removed=linkedSetOf<String>()
        val editor=Proxy.newProxyInstance(javaClass.classLoader,arrayOf(SharedPreferences.Editor::class.java)) {proxy,method,args->
            when(method.name) {
                "remove"->{removed.add(args!![0] as String);proxy}
                "apply"->{writes++;removed.forEach(values::remove);null}
                else->error("Unexpected editor operation: ${method.name}")
            }
        } as SharedPreferences.Editor
        val preferences=Proxy.newProxyInstance(javaClass.classLoader,arrayOf(SharedPreferences::class.java)) {_,method,args->
            when(method.name) {
                "contains"->values.containsKey(args!![0])
                "edit"->editor
                else->error("Unexpected preference operation: ${method.name}")
            }
        } as SharedPreferences
        AppearanceSettings.removeRetiredPreferences(preferences)
        assertEquals(preserved,values)
        assertEquals(AppearanceSettings.retiredKeys,removed)
        assertEquals(1,writes)
        AppearanceSettings.removeRetiredPreferences(preferences)
        assertEquals(1,writes)
    }

    @Test fun amoledOffersClassicAndCustomToggleWindows() {
        val custom=setOf(ConnectionAppearance.DOT_MATRIX,ConnectionAppearance.ARC_REACTOR,ConnectionAppearance.ECLIPSE)
        val toggles=custom+ConnectionAppearance.TOGGLE+ConnectionAppearance.SIDE_ONLY
        assertEquals(toggles,ConnectionAppearance.windowChoices(true).keys)
        assertEquals(ConnectionAppearance.choices,ConnectionAppearance.windowChoices(false))
        for(style in ConnectionAppearance.choices.keys) {
            assertEquals(style,ConnectionAppearance.windowStyle(style,false))
            val effective=ConnectionAppearance.windowStyle(style,true)
            assertEquals(if(style in toggles)style else ConnectionAppearance.SIDE_ONLY,effective)
            val changes=RandomAppearance.changes(mapOf(ConnectionAppearance.STYLE to effective),
                listOf("one","two"),kotlin.random.Random(4),amoled=true)
            assertTrue(changes[ConnectionAppearance.STYLE] in custom)
            assertNotEquals(effective,changes[ConnectionAppearance.STYLE])
        }
    }

    @Test fun immediateRandomizationPreservesInterfaceAndConnectionPolicy() {
        val protected=mapOf(ExperienceModeStore.KEY to "simple",ConnectionAppearance.MERGED to "true",
            "launcherIcon" to "13","selectedServer" to "fixture-server")
        val current=protected+UserThemeColors.keys.zip(AppearancePresets.all.first().colors).toMap()+mapOf(
            PatternWallpaper.KEY to "one",ConnectionAppearance.STYLE to ConnectionAppearance.CLASSIC,
            ConnectionAppearance.EXIT to ConnectionAppearance.DOOR)
        val changes=RandomAppearance.changes(current,listOf("one","two"),kotlin.random.Random(4))
        assertEquals(protected,(current+changes).filterKeys {it in protected})
        assertTrue(changes.keys.intersect(protected.keys).isEmpty())
        assertNotEquals(current[PatternWallpaper.KEY],changes[PatternWallpaper.KEY])
        assertNotEquals(current[ConnectionAppearance.STYLE],changes[ConnectionAppearance.STYLE])
        assertNotEquals(current[ConnectionAppearance.EXIT],changes[ConnectionAppearance.EXIT])
        assertTrue(changes[ConnectionAppearance.STYLE] in ConnectionAppearance.toggleChoices)
        assertEquals(changes[AppTypography.INTERFACE],changes[AppTypography.DISPLAY])
        assertTrue(changes[AppTypography.INTERFACE] in AppTypography.displayChoices)
        assertEquals(changes[AppearanceMotion.MATH],changes[AppearanceMotion.MATH_BODY])
    }
}
